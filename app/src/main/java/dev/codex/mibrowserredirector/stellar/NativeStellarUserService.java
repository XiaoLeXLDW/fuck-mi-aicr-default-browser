/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package dev.codex.mibrowserredirector.stellar;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import com.stellar.server.IStellarService;
import com.stellar.server.IUserServiceCallback;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeStellarUserService {
    public interface Callback {
        void onServiceConnected(IBinder binder);

        void onServiceDisconnected();

        void onServiceStartFailed(int errorCode, String message);
    }

    private static final ConcurrentHashMap<String, ConnectionRecord> CONNECTIONS =
            new ConcurrentHashMap<>();
    private static final Callback NO_OP_CALLBACK = new Callback() {
        @Override
        public void onServiceConnected(IBinder binder) {
        }

        @Override
        public void onServiceDisconnected() {
        }

        @Override
        public void onServiceStartFailed(int errorCode, String message) {
        }
    };

    private static final class ConnectionRecord {
        final NativeUserServiceArgs args;
        volatile Callback callback;
        volatile Handler handler;
        volatile String token;
        volatile IBinder binder;

        ConnectionRecord(NativeUserServiceArgs args, Callback callback, Handler handler) {
            this.args = args;
            this.callback = callback;
            this.handler = handler;
        }
    }

    public static final class ConnectionHandle {
        private final String key;
        private final ConnectionRecord record;

        private ConnectionHandle(String key, ConnectionRecord record) {
            this.key = key;
            this.record = record;
        }
    }

    private NativeStellarUserService() {
    }

    public static ConnectionHandle bindUserService(NativeUserServiceArgs args, Callback callback,
            Handler handler) {
        String key = args.key();
        ConnectionRecord existing = CONNECTIONS.get(key);
        if (existing != null && existing.binder != null && existing.binder.pingBinder()) {
            existing.callback = callback;
            existing.handler = handler;
            dispatch(handler, () -> callback.onServiceConnected(existing.binder));
            return new ConnectionHandle(key, existing);
        }

        IStellarService service;
        String packageName = NativeStellar.getPackageName();
        try {
            service = NativeStellar.requireService();
        } catch (RuntimeException e) {
            dispatch(handler, () -> callback.onServiceStartFailed(-1,
                    "Stellar 服务未连接"));
            return null;
        }
        if (packageName == null) {
            dispatch(handler, () -> callback.onServiceStartFailed(-1,
                    "Stellar 尚未识别应用包名"));
            return null;
        }

        ConnectionRecord record = new ConnectionRecord(args, callback, handler);
        CONNECTIONS.put(key, record);
        ConnectionHandle handle = new ConnectionHandle(key, record);
        IUserServiceCallback aidlCallback = new IUserServiceCallback.Stub() {
            @Override
            public void onServiceConnected(IBinder binder, String returnedVerificationToken) {
                dispatch(record.handler, () -> {
                    if (CONNECTIONS.get(key) != record) return;
                    if (!Objects.equals(args.verificationToken(), returnedVerificationToken)) {
                        stopMismatchedRecord(service, key, record);
                        record.callback.onServiceStartFailed(-2,
                                "服务身份校验失败；旧服务已请求退出");
                        return;
                    }
                    record.binder = binder;
                    record.callback.onServiceConnected(binder);
                });
            }

            @Override
            public void onServiceDisconnected() {
                dispatch(record.handler, () -> {
                    if (!CONNECTIONS.remove(key, record)) return;
                    record.binder = null;
                    record.callback.onServiceDisconnected();
                });
            }

            @Override
            public void onServiceStartFailed(int errorCode, String message) {
                dispatch(record.handler, () -> {
                    if (!CONNECTIONS.remove(key, record)) return;
                    record.callback.onServiceStartFailed(errorCode, message);
                });
            }
        };

        try {
            String token = service.startUserService(args.toBundle(packageName), aidlCallback);
            record.token = token;
            if (token == null) {
                failRecord(key, record, -1, "Stellar 未返回 UserService token");
            }
        } catch (RemoteException | RuntimeException e) {
            failRecord(key, record, -1,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return handle;
    }

    public static IBinder peekUserService(NativeUserServiceArgs args) {
        ConnectionRecord record = CONNECTIONS.get(args.key());
        IBinder binder = record == null ? null : record.binder;
        return binder != null && binder.pingBinder() ? binder : null;
    }

    public static void detachCallback(ConnectionHandle handle, Callback callback) {
        if (handle == null) return;
        ConnectionRecord record = handle.record;
        if (CONNECTIONS.get(handle.key) == record && record.callback == callback) {
            record.callback = NO_OP_CALLBACK;
            record.handler = null;
        }
    }

    public static void unbindUserService(ConnectionHandle handle) throws RemoteException {
        if (handle == null) throw new IllegalStateException("UserService 连接句柄不存在");
        ConnectionRecord record = handle.record;
        if (!CONNECTIONS.remove(handle.key, record)) {
            throw new IllegalStateException("UserService 连接已被更新的页面替换");
        }
        if (record.token == null) throw new IllegalStateException("UserService token 尚未返回");
        NativeStellar.requireService().stopUserService(record.token);
    }

    public static boolean isCurrent(ConnectionHandle handle) {
        return handle != null && CONNECTIONS.get(handle.key) == handle.record;
    }

    private static void stopMismatchedRecord(IStellarService service, String key,
            ConnectionRecord record) {
        CONNECTIONS.remove(key, record);
        String token = record.token;
        if (token == null) return;
        try {
            service.stopUserService(token);
        } catch (RemoteException ignored) {
            // The caller reports the mismatch and may retry after Binder death.
        }
    }

    private static void failRecord(String key, ConnectionRecord record, int errorCode,
            String message) {
        if (!CONNECTIONS.remove(key, record)) return;
        dispatch(record.handler,
                () -> record.callback.onServiceStartFailed(errorCode, message));
    }

    private static void dispatch(Handler handler, Runnable action) {
        if (handler == null) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
