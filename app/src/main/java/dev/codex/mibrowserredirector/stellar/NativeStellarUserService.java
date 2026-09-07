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
        // Published before the synchronous start call, including callback-before-return.
        default void onBindingPrepared(ConnectionHandle handle) { }

        void onServiceConnected(IBinder binder);

        void onServiceDisconnected();

        void onServiceStartFailed(int errorCode, String message);

        // This is a cleanup identity, NEVER permission to invoke the service protocol.
        default void onCleanupRequired(IBinder binder, String message) {
            onServiceStartFailed(-2, message);
        }
    }

    private static final ConcurrentHashMap<String, ConnectionRecord> CONNECTIONS =
            new ConcurrentHashMap<>();
    // Only local state is guarded here. No Binder/manager RPC runs under this lock.
    private static final Object STATE = new Object();

    private static final class ConnectionRecord {
        final NativeUserServiceArgs args;
        final IStellarService sourceManager;
        final IBinder sourceBinder;
        volatile String token;
        volatile IBinder binder;
        ConnectionHandle owner;
        boolean startFinished;
        boolean trusted;
        boolean cleanupRequired;
        boolean disconnected;
        boolean removalBusy;
        String failure;

        ConnectionRecord(NativeUserServiceArgs args, IStellarService sourceManager) {
            this.args = args;
            this.sourceManager = sourceManager;
            this.sourceBinder = sourceManager.asBinder();
        }
    }

    public static final class ConnectionHandle {
        private final String key;
        private final ConnectionRecord record;
        private final Callback callback;
        private final Handler handler;
        private boolean detached;

        private ConnectionHandle(String key, ConnectionRecord record, Callback callback, Handler handler) {
            this.key = key;
            this.record = record;
            this.callback = callback;
            this.handler = handler;
        }
    }

    private NativeStellarUserService() {
    }

    public static ConnectionHandle bindUserService(NativeUserServiceArgs args, Callback callback,
            Handler handler) {
        String key = args.key();
        ConnectionRecord existing = CONNECTIONS.get(key);
        // isBinderAlive is local Binder death knowledge, not a ping RPC on a callback thread.
        if (existing != null && existing.binder != null && !existing.binder.isBinderAlive()) {
            synchronized (STATE) {
                if (!existing.removalBusy && existing.startFinished) CONNECTIONS.remove(key, existing);
            }
        }
        ConnectionHandle reused = null;
        synchronized (STATE) {
            existing = CONNECTIONS.get(key);
            if (existing != null) {
                if (existing.removalBusy) throw new IllegalStateException("原会话正在清理，不能替换连接");
                reused = new ConnectionHandle(key, existing, callback, handler);
                existing.owner = reused;
                STATE.notifyAll();
            }
        }
        if (reused != null) {
            callback.onBindingPrepared(reused);
            notifyCurrent(reused.record);
            return reused;
        }

        IStellarService service = NativeStellar.requireService();
        String packageName = NativeStellar.getPackageName();
        if (packageName == null) throw new IllegalStateException("Stellar 尚未识别应用包名");
        ConnectionRecord record = new ConnectionRecord(args, service);
        ConnectionHandle handle = new ConnectionHandle(key, record, callback, handler);
        synchronized (STATE) {
            if (CONNECTIONS.putIfAbsent(key, record) != null) {
                throw new IllegalStateException("已有连接正在建立，请稍后重试");
            }
            record.owner = handle;
        }
        callback.onBindingPrepared(handle);
        IUserServiceCallback aidlCallback = new IUserServiceCallback.Stub() {
            @Override
            public void onServiceConnected(IBinder binder, String returnedVerificationToken) {
                synchronized (STATE) {
                    if (CONNECTIONS.get(key) != record) return;
                    record.binder = binder; // Retain even a rejected Binder for death confirmation.
                    record.disconnected = false;
                    if (!Objects.equals(args.verificationToken(), returnedVerificationToken)) {
                        record.cleanupRequired = true;
                        record.failure = "服务身份校验失败；保留身份等待受监督清理，尚未确认退出";
                    }
                    // Rejection is sticky: a later callback cannot re-authorize this record.
                    record.trusted = !record.cleanupRequired && binder != null;
                    if (binder == null) {
                        record.cleanupRequired = true;
                        record.failure = "服务返回空 Binder；退出状态未确认";
                    }
                    STATE.notifyAll();
                }
                notifyCurrent(record);
            }

            @Override
            public void onServiceDisconnected() {
                synchronized (STATE) {
                    if (CONNECTIONS.get(key) != record) return;
                    record.disconnected = true;
                    STATE.notifyAll();
                }
                notifyCurrent(record);
            }

            @Override
            public void onServiceStartFailed(int errorCode, String message) {
                requireCleanup(record, "管理器报告启动失败（" + errorCode + "）；清理未确认");
            }
        };

        try {
            String token = service.startUserService(args.toBundle(packageName), aidlCallback);
            boolean needsCleanup;
            synchronized (STATE) {
                record.token = token;
                record.startFinished = true;
                if (token == null) {
                    record.cleanupRequired = true;
                    record.failure = "Stellar 未返回停止 token；清理未确认";
                }
                needsCleanup = record.cleanupRequired;
                STATE.notifyAll();
            }
            // Wake an existing bounded removal or report a late cleanup identity to its owner.
            if (needsCleanup) notifyCurrent(record);
        } catch (RemoteException | RuntimeException e) {
            synchronized (STATE) { record.startFinished = true; STATE.notifyAll(); }
            requireCleanup(record, "启动调用失败（" + e.getClass().getSimpleName() + "）；保留清理身份");
        }
        return handle;
    }

    public static IBinder peekUserService(NativeUserServiceArgs args) {
        ConnectionRecord record = CONNECTIONS.get(args.key());
        synchronized (STATE) {
            if (record == null || !record.trusted || record.cleanupRequired) return null;
            return record.binder;
        }
    }

    public static void detachCallback(ConnectionHandle handle, Callback callback) {
        if (handle == null) return;
        synchronized (STATE) {
            if (handle.callback != callback) return;
            handle.detached = true;
            if (handle.record.owner == handle) handle.record.owner = null;
            STATE.notifyAll();
        }
    }

    /** Only called from the interruptible, bounded stop worker, never a callback/UI thread. */
    public static void unbindUserService(ConnectionHandle handle) throws RemoteException, InterruptedException {
        ConnectionRecord record;
        synchronized (STATE) {
            requireCurrent(handle);
            record = handle.record;
            if (record.removalBusy) throw new IllegalStateException("原会话停止调用尚未结束");
            record.removalBusy = true;
        }
        try {
            String token;
            synchronized (STATE) {
                // The caller's stop deadline interrupts this wait; timeout never forgets identity.
                while (record.token == null && !record.startFinished) {
                    requireCurrent(handle);
                    STATE.wait();
                }
                requireCurrent(handle);
                token = record.token;
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (token == null) throw new IllegalStateException("停止 token 未取得，仍保留原会话");
            if (record.sourceBinder == null || !record.sourceBinder.isBinderAlive()) {
                throw new IllegalStateException("来源管理器已断开，不向替代管理器发送旧 token");
            }
            record.sourceManager.stopUserService(token);
            // A successful RPC is NOT Binder death. Only confirmedStopped may forget the record.
        } finally {
            synchronized (STATE) { record.removalBusy = false; STATE.notifyAll(); }
        }
    }

    public static boolean isCurrent(ConnectionHandle handle) {
        synchronized (STATE) {
            return handle != null && !handle.detached && handle.record.owner == handle
                    && CONNECTIONS.get(handle.key) == handle.record;
        }
    }

    public static IBinder cleanupBinder(ConnectionHandle handle) {
        synchronized (STATE) { return isCurrent(handle) ? handle.record.binder : null; }
    }

    public static boolean allowsServiceCommands(ConnectionHandle handle) {
        synchronized (STATE) {
            return isCurrent(handle) && handle.record.trusted && !handle.record.cleanupRequired;
        }
    }

    /** Called by the protocol verifier; no RPC or recursive callback from this local mutation. */
    public static void rejectBusinessProtocol(ConnectionHandle handle) {
        synchronized (STATE) {
            if (!isCurrent(handle)) return;
            handle.record.cleanupRequired = true;
            handle.record.trusted = false;
            handle.record.failure = "服务协议未通过校验，仅允许通过来源管理器清理";
            STATE.notifyAll();
        }
    }

    /** Caller must have independently confirmed death of this record's Binder. No RPC here. */
    public static void confirmedStopped(ConnectionHandle handle) {
        synchronized (STATE) {
            if (!isCurrent(handle)) return;
            CONNECTIONS.remove(handle.key, handle.record);
            handle.record.owner = null;
            handle.detached = true;
            STATE.notifyAll();
        }
    }

    private static void requireCurrent(ConnectionHandle handle) {
        if (!isCurrent(handle)) throw new IllegalStateException("旧页面没有当前会话的移除权限");
    }

    private static void requireCleanup(ConnectionRecord record, String message) {
        synchronized (STATE) {
            record.cleanupRequired = true;
            record.trusted = false;
            record.failure = message;
            STATE.notifyAll();
        }
        notifyCurrent(record);
    }

    private static void notifyCurrent(ConnectionRecord record) {
        ConnectionHandle owner;
        synchronized (STATE) { owner = record.owner; }
        if (owner == null) return;
        dispatch(owner.handler, () -> {
            boolean cleanup, disconnected;
            IBinder binder;
            String failure;
            synchronized (STATE) {
                if (!isCurrent(owner)) return;
                cleanup = record.cleanupRequired;
                disconnected = record.disconnected;
                binder = record.binder;
                failure = record.failure;
            }
            // No calls under STATE. The adapter must only enqueue cleanup, not run RPC here.
            if (cleanup) owner.callback.onCleanupRequired(binder, failure);
            else if (disconnected) owner.callback.onServiceDisconnected();
            else if (binder != null) owner.callback.onServiceConnected(binder);
        });
    }

    private static void dispatch(Handler handler, Runnable action) {
        if (handler == null) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
