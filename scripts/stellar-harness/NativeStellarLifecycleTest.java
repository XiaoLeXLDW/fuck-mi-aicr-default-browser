package dev.codex.mibrowserredirector.stellar;

import android.os.*;
import com.stellar.server.*;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Runs the real NativeStellarUserService source with controllable transport, not copied logic. */
public class NativeStellarLifecycleTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String VERIFICATION = "synthetic-test-only";
    private static final class RemoteBinder implements IBinder {
        volatile boolean alive = true;
        public boolean pingBinder() { return alive; }
    }
    private static class Manager implements IStellarService {
        final RemoteBinder binder = new RemoteBinder();
        final AtomicInteger stops = new AtomicInteger();
        volatile IUserServiceCallback callback;
        volatile boolean failStop;
        volatile CountDownLatch startRelease, stopRelease;
        final CountDownLatch started = new CountDownLatch(1);
        public IBinder asBinder() { return binder; }
        public String startUserService(Bundle args, IUserServiceCallback callback) {
            this.callback = callback;
            started.countDown();
            await(startRelease);
            return "token-A";
        }
        public void stopUserService(String token) throws RemoteException {
            stops.incrementAndGet();
            await(stopRelease);
            if (failStop) throw new RemoteException("injected stop failure");
        }
    }
    private static class Events implements NativeStellarUserService.Callback {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();
        public void onServiceConnected(IBinder binder) { connections.incrementAndGet(); }
        public void onServiceDisconnected() { }
        public void onServiceStartFailed(int code, String message) { failures.incrementAndGet(); }
    }
    private static NativeUserServiceArgs args() {
        return new NativeUserServiceArgs(NativeStellarLifecycleTest.class,
                "case-" + IDS.incrementAndGet(), 1, VERIFICATION);
    }
    private static void await(CountDownLatch latch) {
        if (latch == null) return;
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("fixture wait timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test public void mismatchCallbackMustNotCallBlockingManagerOnCallbackThread() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        manager.stopRelease = new CountDownLatch(1);
        Events events = new Events();
        NativeStellarUserService.bindUserService(args(), events, new Handler());
        Thread callbackThread = new Thread(() -> manager.callback.onServiceConnected(new RemoteBinder(), "wrong"));
        callbackThread.start();
        try {
            callbackThread.join(150);
            assertFalse("identity rejection blocked callback thread in stopUserService", callbackThread.isAlive());
            assertEquals("identity error must be reported promptly", 1, events.failures.get());
            assertEquals("callback must not execute stop RPC", 0, manager.stops.get());
        } finally { manager.stopRelease.countDown(); callbackThread.join(2000); }
    }

    @Test public void lateTokenKeepsRejectedIdentityAvailableForSupervisedCleanup() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        manager.startRelease = new CountDownLatch(1);
        AtomicReference<NativeStellarUserService.ConnectionHandle> handle = new AtomicReference<>();
        NativeUserServiceArgs args = args(); Events events = new Events();
        Thread bind = new Thread(() -> handle.set(NativeStellarUserService.bindUserService(args, events, new Handler())));
        bind.start(); await(manager.started);
        manager.callback.onServiceConnected(new RemoteBinder(), "wrong");
        manager.startRelease.countDown(); bind.join(2000);
        assertTrue("late token lost its cleanup identity", NativeStellarUserService.isCurrent(handle.get()));
        NativeStellarUserService.unbindUserService(handle.get());
        assertEquals(1, manager.stops.get());
        assertEquals(0, events.connections.get());
    }

    @Test public void stopUsesCreatingManagerNotLatestStaticManager() throws Exception {
        Manager a = new Manager(); NativeStellar.service = a;
        NativeStellarUserService.ConnectionHandle handle = NativeStellarUserService.bindUserService(args(), new Events(), new Handler());
        Manager b = new Manager(); NativeStellar.service = b;
        NativeStellarUserService.unbindUserService(handle);
        assertEquals("stop routed to wrong manager", 1, a.stops.get());
        assertEquals(0, b.stops.get());
    }

    @Test public void failedRemovalCanRetryWithSameHandle() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        NativeStellarUserService.ConnectionHandle handle = NativeStellarUserService.bindUserService(args(), new Events(), new Handler());
        manager.failStop = true;
        try { NativeStellarUserService.unbindUserService(handle); fail("expected injected failure"); }
        catch (RemoteException expected) { }
        manager.failStop = false;
        NativeStellarUserService.unbindUserService(handle);
        assertEquals(2, manager.stops.get());
    }

    @Test public void oldHandleCannotRemoveRecordAdoptedByNewPage() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        NativeUserServiceArgs args = args();
        NativeStellarUserService.ConnectionHandle old = NativeStellarUserService.bindUserService(args, new Events(), new Handler());
        manager.callback.onServiceConnected(new RemoteBinder(), VERIFICATION);
        NativeStellarUserService.ConnectionHandle current = NativeStellarUserService.bindUserService(args, new Events(), new Handler());
        try { NativeStellarUserService.unbindUserService(old); fail("stale page removed current lease"); }
        catch (IllegalStateException expected) { }
        assertTrue(NativeStellarUserService.isCurrent(current));
        assertEquals(0, manager.stops.get());
    }

    @Test public void preparedHandleExistsBeforeStartReturns() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        manager.startRelease = new CountDownLatch(1);
        AtomicReference<NativeStellarUserService.ConnectionHandle> prepared = new AtomicReference<>();
        Thread bind = new Thread(() -> NativeStellarUserService.bindUserService(args(), new Events() {
            @Override public void onBindingPrepared(NativeStellarUserService.ConnectionHandle handle) { prepared.set(handle); }
        }, new Handler()));
        bind.start(); await(manager.started);
        try { assertNotNull(prepared.get()); assertTrue(NativeStellarUserService.isCurrent(prepared.get())); }
        finally { manager.startRelease.countDown(); bind.join(2000); }
    }

    @Test public void rejectedBinderIsRetainedButNeverExposedAsBusinessEndpoint() {
        Manager manager = new Manager(); NativeStellar.service = manager;
        NativeUserServiceArgs args = args();
        NativeStellarUserService.ConnectionHandle handle = NativeStellarUserService.bindUserService(args, new Events(), new Handler());
        RemoteBinder rejected = new RemoteBinder(); manager.callback.onServiceConnected(rejected, "wrong");
        assertSame(rejected, NativeStellarUserService.cleanupBinder(handle));
        assertFalse(NativeStellarUserService.allowsServiceCommands(handle));
        assertNull(NativeStellarUserService.peekUserService(args));
        manager.callback.onServiceConnected(rejected, VERIFICATION);
        assertFalse("rejection must stay sticky", NativeStellarUserService.allowsServiceCommands(handle));
    }

    @Test public void stopRpcSuccessAloneDoesNotForgetIdentity() throws Exception {
        Manager manager = new Manager(); NativeStellar.service = manager;
        NativeStellarUserService.ConnectionHandle handle = NativeStellarUserService.bindUserService(args(), new Events(), new Handler());
        NativeStellarUserService.unbindUserService(handle);
        assertTrue("RPC return is not proof of death", NativeStellarUserService.isCurrent(handle));
        NativeStellarUserService.confirmedStopped(handle);
        assertFalse(NativeStellarUserService.isCurrent(handle));
    }

    @Test public void protocolRejectionSurvivesRebindAndKeepsOriginalCleanupIdentity() throws Exception {
        Manager a = new Manager(); NativeStellar.service = a;
        NativeUserServiceArgs args = args();
        NativeStellarUserService.ConnectionHandle first = NativeStellarUserService.bindUserService(args, new Events(), new Handler());
        RemoteBinder binder = new RemoteBinder(); a.callback.onServiceConnected(binder, VERIFICATION);
        assertTrue(NativeStellarUserService.allowsServiceCommands(first));
        NativeStellarUserService.rejectBusinessProtocol(first);
        Manager b = new Manager(); NativeStellar.service = b;
        Events events = new Events();
        NativeStellarUserService.ConnectionHandle next = NativeStellarUserService.bindUserService(args, events, new Handler());
        assertFalse(NativeStellarUserService.allowsServiceCommands(next));
        assertSame(binder, NativeStellarUserService.cleanupBinder(next));
        assertEquals(1, events.failures.get()); assertEquals(0, events.connections.get());
        NativeStellarUserService.unbindUserService(next);
        assertEquals(1, a.stops.get()); assertEquals(0, b.stops.get());
    }

    @Test public void oldQueuedCallbackAndDetachDoNotAffectNewOwner() {
        Manager manager = new Manager(); NativeStellar.service = manager;
        java.util.ArrayDeque<Runnable> callbacks = new java.util.ArrayDeque<>();
        Handler handler = new Handler(callbacks::add);
        NativeUserServiceArgs args = args(); Events oldEvents = new Events(), newEvents = new Events();
        NativeStellarUserService.ConnectionHandle old = NativeStellarUserService.bindUserService(args, oldEvents, handler);
        manager.callback.onServiceConnected(new RemoteBinder(), VERIFICATION);
        NativeStellarUserService.ConnectionHandle current = NativeStellarUserService.bindUserService(args, newEvents, handler);
        NativeStellarUserService.detachCallback(old, oldEvents);
        while (!callbacks.isEmpty()) callbacks.remove().run();
        assertEquals(0, oldEvents.connections.get()); assertEquals(1, newEvents.connections.get());
        assertTrue(NativeStellarUserService.isCurrent(current));
        NativeStellarUserService.confirmedStopped(old);
        assertTrue("old completion cleared new lease", NativeStellarUserService.isCurrent(current));
    }

    @Test public void deadSourceManagerDoesNotFallBackToReplacement() throws Exception {
        Manager a = new Manager(); NativeStellar.service = a;
        NativeStellarUserService.ConnectionHandle handle = NativeStellarUserService.bindUserService(args(), new Events(), new Handler());
        a.binder.alive = false; Manager b = new Manager(); NativeStellar.service = b;
        try { NativeStellarUserService.unbindUserService(handle); fail("must reject dead source"); }
        catch (IllegalStateException expected) { }
        assertEquals(0, b.stops.get()); assertTrue(NativeStellarUserService.isCurrent(handle));
    }
}
