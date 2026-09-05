package dev.codex.mibrowserredirector.privilege;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;

import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendId;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendSnapshot;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.Listener;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceCallback;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceSession;
import rikka.shizuku.Shizuku;

final class ShizukuBackendAdapter implements PrivilegeRuntime.BackendAdapter {
    private final Handler callbackHandler;
    private final Shizuku.UserServiceArgs serviceArgs;
    private final ShizukuTagOwnership tagOwnership;
    private Listener listener;
    private boolean observing;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> notifyAvailability(true);
    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> notifyAvailability(false);
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                Listener current = listener;
                if (current != null) {
                    current.onPermissionResult(
                            BackendId.SHIZUKU,
                            requestCode,
                            grantResult == PackageManager.PERMISSION_GRANTED
                    );
                }
            };

    ShizukuBackendAdapter(Context context, Handler callbackHandler, Class<?> serviceClass,
            String processSuffix, String tag, int generation) {
        this.callbackHandler = callbackHandler;
        // Match ShizukuServiceConnections.get(): explicit tag, otherwise class name; NOT version.
        this.tagOwnership = ShizukuTagOwnership.forTag(tag != null ? tag : serviceClass.getName());
        this.serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context, serviceClass))
                .processNameSuffix(processSuffix)
                .tag(tag)
                .version(generation)
                .daemon(true)
                .debuggable(false);
    }

    @Override
    public BackendId id() {
        return BackendId.SHIZUKU;
    }

    @Override
    public BackendSnapshot snapshot() {
        boolean available = Shizuku.pingBinder();
        boolean authorized = available
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        boolean rationale = available && !authorized
                && Shizuku.shouldShowRequestPermissionRationale();
        return new BackendSnapshot(
                id(),
                available,
                authorized,
                rationale,
                available ? Shizuku.getUid() : -1,
                available ? Shizuku.getVersion() : -1
        );
    }

    @Override
    public void startObserving(Listener listener) {
        if (observing) throw new IllegalStateException("Shizuku adapter already observing");
        this.listener = listener;
        observing = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, callbackHandler);
        Shizuku.addBinderDeadListener(binderDeadListener, callbackHandler);
        Shizuku.addRequestPermissionResultListener(permissionResultListener, callbackHandler);
    }

    @Override
    public void stopObserving() {
        if (!observing) return;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        observing = false;
        listener = null;
    }

    @Override
    public void requestPermission(int requestCode) {
        Shizuku.requestPermission(requestCode);
    }

    @Override
    public ServiceSession bind(ServiceCallback callback) {
        IBinder managerBinder = Shizuku.getBinder();
        if (managerBinder == null) {
            throw new IllegalStateException("Shizuku 服务 Binder 不可用");
        }
        ShizukuSession session = new ShizukuSession(callback, managerBinder);
        try {
            tagOwnership.bind(session.lease, () -> {
                session.requireSourceManager();
                Shizuku.bindUserService(serviceArgs, session.connection);
            });
        } catch (RuntimeException error) {
            session.current = false;
            callbackHandler.post(() -> callback.onServiceStartFailed(
                    session, -1, compactError(error)));
        }
        return session;
    }

    private void notifyAvailability(boolean available) {
        Listener current = listener;
        if (current != null) current.onAvailabilityChanged(id(), available);
    }

    private static String compactError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private final class ShizukuSession implements ServiceSession {
        private static final ServiceCallback NO_OP_CALLBACK = new ServiceCallback() {
            @Override
            public void onServiceConnected(ServiceSession session, IBinder binder) {
            }

            @Override
            public void onServiceDisconnected(ServiceSession session) {
            }

            @Override
            public void onServiceStartFailed(ServiceSession session, int errorCode,
                    String message) {
            }
        };

        private volatile ServiceCallback callback;
        private volatile boolean current = true;
        private volatile boolean removed;
        private final ShizukuTagOwnership.Lease lease = tagOwnership.newLease();
        private final IBinder managerBinder;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                callbackHandler.post(() -> callback.onServiceConnected(
                        ShizukuSession.this, binder));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                current = false;
                callbackHandler.post(() -> callback.onServiceDisconnected(
                        ShizukuSession.this));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                onServiceDisconnected(name);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                current = false;
                callbackHandler.post(() -> callback.onServiceStartFailed(
                        ShizukuSession.this, -1, "Shizuku 返回空 UserService Binder"));
            }
        };

        ShizukuSession(ServiceCallback callback, IBinder managerBinder) {
            this.callback = callback;
            this.managerBinder = managerBinder;
        }

        @Override
        public BackendId backendId() {
            return BackendId.SHIZUKU;
        }

        @Override
        public void remove() {
            tagOwnership.remove(lease, () -> {
                requireSourceManager();
                Shizuku.unbindUserService(serviceArgs, connection, true);
                removed = true;
            });
        }

        @Override
        public void detach() {
            // Always retire THIS callback, even when a newer lease forbids SDK-wide unbinding.
            callback = NO_OP_CALLBACK;
            current = false;
            try {
                tagOwnership.detach(lease, () -> {
                    if (removed) return;
                    requireSourceManager();
                    Shizuku.unbindUserService(serviceArgs, connection, false);
                });
            } catch (RuntimeException ignored) {
                // A later Activity can reattach; daemon ownership is preserved.
            }
        }

        @Override
        public boolean isCurrent() {
            return current && tagOwnership.isCurrent(lease) && Shizuku.getBinder() == managerBinder;
        }

        private void requireSourceManager() {
            if (Shizuku.getBinder() != managerBinder || !managerBinder.pingBinder()
                    || Shizuku.getBinder() != managerBinder) {
                throw new IllegalStateException(
                        "Shizuku 管理器 Binder 已变化，拒绝向非来源管理器操作服务");
            }
        }
    }
}
