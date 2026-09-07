package dev.codex.mibrowserredirector;

import android.os.*;
import com.stellar.server.*;
import dev.codex.mibrowserredirector.stellar.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Integrates the actual native record with the actual total-deadline stop coordinator. */
public class StellarSupervisedCleanupTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final class RemoteBinder implements IBinder {
        volatile boolean alive = true;
        public boolean pingBinder() { return alive; }
    }
    private static final class Manager implements IStellarService {
        final RemoteBinder managerBinder = new RemoteBinder(), child = new RemoteBinder();
        final CountDownLatch started = new CountDownLatch(1), returnToken = new CountDownLatch(1);
        final AtomicInteger stops = new AtomicInteger();
        volatile IUserServiceCallback callback;
        volatile CountDownLatch stopEntered, releaseStop;
        public IBinder asBinder() { return managerBinder; }
        public String startUserService(Bundle args, IUserServiceCallback callback) {
            this.callback = callback; started.countDown();
            callback.onServiceConnected(child, "mismatched-verification");
            await(returnToken);
            return "synthetic-cleanup-token";
        }
        public void stopUserService(String token) {
            stops.incrementAndGet();
            if (stopEntered != null) stopEntered.countDown();
            // Model a Binder RPC which ignores Java interruption until externally released.
            if (releaseStop != null) {
                boolean interrupted = false;
                while (releaseStop.getCount() != 0) {
                    try { releaseStop.await(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            child.alive = false;
        }
    }
    private static final class Owner implements NativeStellarUserService.Callback {
        volatile NativeStellarUserService.ConnectionHandle handle;
        final AtomicInteger rejected = new AtomicInteger();
        public void onBindingPrepared(NativeStellarUserService.ConnectionHandle handle) { this.handle = handle; }
        public void onServiceConnected(IBinder binder) { throw new AssertionError("rejected Binder exposed"); }
        public void onServiceDisconnected() { }
        public void onServiceStartFailed(int code, String message) { rejected.incrementAndGet(); }
    }
    private static Thread bind(Manager manager, Owner owner) {
        NativeStellar.service = manager;
        Thread thread = new Thread(() -> NativeStellarUserService.bindUserService(
                new NativeUserServiceArgs(StellarSupervisedCleanupTest.class, "case-" + IDS.incrementAndGet(), 1, "expected"),
                owner, new Handler()));
        thread.start(); await(manager.started); return thread;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static UserServiceStopper.Result stop(Owner owner, long budget) {
        return UserServiceStopper.stop(new UserServiceStopper.Endpoint() {
            public String disable() { return "rejected identity: manager only"; }
            public void destroy() { /* A rejected Binder is not a destroy endpoint. */ }
            public boolean isAlive() {
                IBinder binder = NativeStellarUserService.cleanupBinder(owner.handle);
                return binder == null || binder.isBinderAlive();
            }
        }, () -> NativeStellarUserService.unbindUserService(owner.handle), budget);
    }
    private static void released() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (UserServiceStopper.hasPendingCalls() && System.nanoTime() < deadline) Thread.sleep(2);
        assertFalse("fixture left an occupied stop worker", UserServiceStopper.hasPendingCalls());
    }

    @Test public void tokenArrivingWithinBudgetCompletesBoundedCleanup() throws Exception {
        Manager manager = new Manager(); Owner owner = new Owner(); Thread binding = bind(manager, owner);
        AtomicReference<UserServiceStopper.Result> result = new AtomicReference<>();
        Thread stopping = new Thread(() -> result.set(stop(owner, 800))); stopping.start();
        manager.returnToken.countDown(); binding.join(2000); stopping.join(2000);
        assertNotNull(result.get()); assertTrue(result.get().detail, result.get().stopped);
        assertEquals(1, manager.stops.get()); assertTrue(owner.rejected.get() > 0); released();
    }

    @Test public void tokenAfterDeadlineIsRetainedForExplicitRetry() throws Exception {
        Manager manager = new Manager(); Owner owner = new Owner(); Thread binding = bind(manager, owner);
        try {
            UserServiceStopper.Result first = stop(owner, 40);
            assertFalse(first.stopped); released();
            assertEquals(0, manager.stops.get());
            assertTrue(NativeStellarUserService.isCurrent(owner.handle));
        } finally { manager.returnToken.countDown(); binding.join(2000); }
        assertEquals("expired request must not spontaneously send a late RPC", 0, manager.stops.get());
        assertTrue(stop(owner, 800).stopped); assertEquals(1, manager.stops.get()); released();
    }

    @Test public void hungManagerDoesNotBlockCallbackOrPermitDuplicateStop() throws Exception {
        Manager manager = new Manager(); manager.returnToken.countDown();
        manager.stopEntered = new CountDownLatch(1); manager.releaseStop = new CountDownLatch(1);
        Owner owner = new Owner(); Thread binding = bind(manager, owner); binding.join(2000);
        try {
            UserServiceStopper.Result first = stop(owner, 50);
            assertFalse(first.stopped); assertTrue(UserServiceStopper.hasPendingCalls());
            assertFalse(stop(owner, 50).stopped); assertEquals(1, manager.stops.get());
            assertTrue(NativeStellarUserService.isCurrent(owner.handle));
        } finally { manager.releaseStop.countDown(); released(); }
        assertTrue(stop(owner, 800).stopped); released();
    }
}
