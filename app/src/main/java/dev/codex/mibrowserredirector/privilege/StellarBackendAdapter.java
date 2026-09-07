package dev.codex.mibrowserredirector.privilege;

import android.os.Handler;
import android.os.IBinder;

import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendId;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendSnapshot;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.Listener;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceCallback;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceSession;
import dev.codex.mibrowserredirector.stellar.NativeStellar;
import dev.codex.mibrowserredirector.stellar.NativeStellarUserService;
import dev.codex.mibrowserredirector.stellar.NativeUserServiceArgs;

final class StellarBackendAdapter implements PrivilegeRuntime.BackendAdapter {
    private final Handler callbackHandler;
    private final NativeUserServiceArgs serviceArgs;
    private Listener listener;
    private boolean observing;

    private final NativeStellar.BinderReceivedListener binderReceivedListener =
            () -> notifyAvailability(true);
    private final NativeStellar.BinderDeadListener binderDeadListener =
            () -> notifyAvailability(false);
    private final NativeStellar.PermissionResultListener permissionResultListener =
            (requestCode, allowed) -> {
                Listener current = listener;
                if (current != null) {
                    current.onPermissionResult(BackendId.STELLAR, requestCode, allowed);
                }
            };

    StellarBackendAdapter(Handler callbackHandler, Class<?> serviceClass,
            String processSuffix, int generation, String verificationToken) {
        this.callbackHandler = callbackHandler;
        this.serviceArgs = new NativeUserServiceArgs(
                serviceClass, processSuffix, generation, verificationToken);
    }

    @Override
    public BackendId id() {
        return BackendId.STELLAR;
    }

    @Override
    public BackendSnapshot snapshot() {
        boolean available = NativeStellar.pingBinder();
        boolean authorized = available && NativeStellar.checkSelfPermission();
        boolean rationale = available && !authorized
                && NativeStellar.shouldShowRequestPermissionRationale();
        return new BackendSnapshot(
                id(),
                available,
                authorized,
                rationale,
                NativeStellar.getServerUid(),
                NativeStellar.getServerVersion()
        );
    }

    @Override
    public void startObserving(Listener listener) {
        if (observing) throw new IllegalStateException("Stellar adapter already observing");
        this.listener = listener;
        observing = true;
        NativeStellar.addBinderReceivedListenerSticky(binderReceivedListener);
        NativeStellar.addBinderDeadListener(binderDeadListener);
        NativeStellar.addPermissionResultListener(permissionResultListener);
    }

    @Override
    public void stopObserving() {
        if (!observing) return;
        NativeStellar.removeBinderReceivedListener(binderReceivedListener);
        NativeStellar.removeBinderDeadListener(binderDeadListener);
        NativeStellar.removePermissionResultListener(permissionResultListener);
        observing = false;
        listener = null;
    }

    @Override
    public void requestPermission(int requestCode) throws Exception {
        NativeStellar.requestPermission(requestCode);
    }

    @Override
    public ServiceSession bind(ServiceCallback callback) {
        StellarSession session = new StellarSession(callback);
        NativeStellarUserService.ConnectionHandle handle =
                NativeStellarUserService.bindUserService(
                        serviceArgs, session.nativeCallback, callbackHandler);
        session.handle = handle;
        return session;
    }

    private void notifyAvailability(boolean available) {
        Listener current = listener;
        if (current != null) current.onAvailabilityChanged(id(), available);
    }

    private static final class StellarSession implements ServiceSession {
        private final ServiceCallback callback;
        private final NativeStellarUserService.Callback nativeCallback;
        private volatile NativeStellarUserService.ConnectionHandle handle;

        StellarSession(ServiceCallback callback) {
            this.callback = callback;
            this.nativeCallback = new NativeStellarUserService.Callback() {
                    @Override
                    public void onBindingPrepared(NativeStellarUserService.ConnectionHandle prepared) {
                        handle = prepared;
                    }

                    @Override
                    public void onCleanupRequired(IBinder binder, String message) {
                        StellarSession.this.callback.onCleanupRequired(StellarSession.this, binder, message);
                    }

                    @Override
                    public void onServiceConnected(IBinder binder) {
                        StellarSession.this.callback.onServiceConnected(
                                StellarSession.this, binder);
                    }

                    @Override
                    public void onServiceDisconnected() {
                        StellarSession.this.callback.onServiceDisconnected(
                                StellarSession.this);
                    }

                    @Override
                    public void onServiceStartFailed(int errorCode, String message) {
                        StellarSession.this.callback.onServiceStartFailed(
                                StellarSession.this, errorCode, message);
                    }
                };
        }

        @Override
        public BackendId backendId() {
            return BackendId.STELLAR;
        }

        @Override
        public void remove() throws Exception {
            NativeStellarUserService.unbindUserService(handle);
        }

        @Override public IBinder cleanupBinder() {
            return NativeStellarUserService.cleanupBinder(handle);
        }

        @Override public boolean allowsServiceCommands(IBinder candidate) {
            return NativeStellarUserService.allowsServiceCommands(handle);
        }

        @Override public void rejectServiceCommands(IBinder rejected) {
            NativeStellarUserService.rejectBusinessProtocol(handle);
        }

        @Override public void onStopConfirmed() {
            NativeStellarUserService.confirmedStopped(handle);
        }

        @Override
        public void detach() {
            NativeStellarUserService.detachCallback(handle, nativeCallback);
        }

        @Override
        public boolean isCurrent() {
            return NativeStellarUserService.isCurrent(handle);
        }
    }
}
