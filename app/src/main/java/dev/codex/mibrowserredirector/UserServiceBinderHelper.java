package dev.codex.mibrowserredirector;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.atomic.AtomicBoolean;

/** Common Binder death and destroy helpers used by both privilege backends. */
public final class UserServiceBinderHelper {
    // Shizuku reserves transaction 16,777,115. AIDL method id 16,777,114
    // becomes that code after FIRST_CALL_TRANSACTION is added.
    private static final int TRANSACTION_DESTROY =
            IBinder.FIRST_CALL_TRANSACTION + 16_777_114;

    private UserServiceBinderHelper() {
    }

    public static DeathMonitor watchDeath(IBinder binder) {
        return new DeathMonitor(binder);
    }

    static int destroyTransactionCode() {
        return TRANSACTION_DESTROY;
    }

    public static void destroy(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            if (!binder.transact(TRANSACTION_DESTROY, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("UserService destroy transaction was rejected");
            }
        } finally {
            data.recycle();
        }
    }

    public static boolean isAlive(IBinder binder) {
        // Require both Binder liveness signals to be false before declaring death.
        boolean binderAlive = binder.isBinderAlive();
        boolean pingAlive = binder.pingBinder();
        return binderAlive || pingAlive;
    }

    public static final class DeathMonitor implements AutoCloseable {
        private final IBinder binder;
        private final AtomicBoolean deathObserved = new AtomicBoolean(false);
        private final IBinder.DeathRecipient recipient = () -> deathObserved.set(true);
        private boolean linked;

        private DeathMonitor(IBinder binder) {
            this.binder = binder;
            try {
                binder.linkToDeath(recipient, 0);
                linked = true;
            } catch (RemoteException e) {
                deathObserved.set(true);
            }
            if (!UserServiceBinderHelper.isAlive(binder)) {
                deathObserved.set(true);
            }
        }

        public boolean isAlive() {
            return !deathObserved.get() && UserServiceBinderHelper.isAlive(binder);
        }

        @Override
        public void close() {
            if (!linked) return;
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (RuntimeException ignored) {
                // Binder death may already have removed the recipient.
            }
            linked = false;
        }
    }
}
