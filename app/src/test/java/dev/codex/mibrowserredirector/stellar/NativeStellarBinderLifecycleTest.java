package dev.codex.mibrowserredirector.stellar;

import android.os.Bundle;
import android.os.IBinder;

import com.stellar.server.IStellarApplication;
import com.stellar.server.IStellarService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual manager-connection source with AGP's mock Android runtime; no device or copied logic. */
public class NativeStellarBinderLifecycleTest {
    private static final class Manager {
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        final List<IStellarApplication> callbacks = new ArrayList<>();
        IStellarApplication registeredClient;
        final IBinder binder;
        boolean attachImmediately = true;
        boolean permission = true;
        int rationaleQueries;
        Runnable duringAttach;
        CountDownLatch permissionEntered;
        CountDownLatch permissionRelease;

        Manager() {
            IStellarService service = (IStellarService) Proxy.newProxyInstance(
                    IStellarService.class.getClassLoader(), new Class<?>[]{IStellarService.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "attachApplication":
                                IStellarApplication callback = (IStellarApplication) args[0];
                                callbacks.add(callback);
                                // Stellar keeps ClientRecord.client from the FIRST attach for a UID/PID.
                                if (registeredClient == null) registeredClient = callback;
                                if (duringAttach != null) duringAttach.run();
                                if (attachImmediately) callback.bindApplication(new Bundle());
                                return null;
                            case "checkSelfPermission":
                                if (permissionEntered != null) permissionEntered.countDown();
                                if (permissionRelease != null
                                        && !permissionRelease.await(2, TimeUnit.SECONDS)) {
                                    throw new AssertionError("permission fixture timed out");
                                }
                                return permission;
                            case "requestPermission":
                                registeredClient.dispatchRequestPermissionResult((Integer) args[1], new Bundle());
                                return null;
                            case "shouldShowRequestPermissionRationale":
                                rationaleQueries++;
                                return false;
                            default:
                                throw new AssertionError("Unexpected manager call: " + method.getName());
                        }
                    });
            binder = (IBinder) Proxy.newProxyInstance(
                    IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toString": return "SyntheticStellarManagerBinder";
                            case "queryLocalInterface": return service;
                            case "isBinderAlive":
                            case "pingBinder": return true;
                            case "linkToDeath":
                                deaths.add((IBinder.DeathRecipient) args[0]);
                                return null;
                            // A death callback can already be queued when unlink returns false.
                            case "unlinkToDeath": return false;
                            default:
                                throw new AssertionError("Unexpected Binder call: " + method.getName());
                        }
                    });
        }

        void connect() {
            NativeStellar.acceptBinder(binder, "synthetic.test.app");
        }

        IStellarApplication callback() {
            return callbacks.get(callbacks.size() - 1);
        }
    }

    @Before public void resetConnection() {
        NativeStellar.clearBinder();
    }

    @After public void clearConnection() {
        NativeStellar.clearBinder();
    }

    @Test public void queuedOldDeathCannotClearReplacementManager() {
        Manager old = new Manager(); old.connect();
        IBinder.DeathRecipient queuedDeath = old.deaths.get(0);
        Manager replacement = new Manager(); replacement.connect();

        queuedDeath.binderDied();

        assertSame("old death discarded the live replacement", replacement.binder, NativeStellar.getBinder());
        assertTrue(NativeStellar.pingBinder());
        assertSame(replacement.callback().asBinder(), NativeStellar.getClientBinder());
    }

    @Test public void sameBinderRetryPreservesRegisteredClientAndPermissionResults() throws Exception {
        Manager manager = new Manager(); manager.attachImmediately = false;
        manager.connect();
        manager.connect();

        assertEquals(2, manager.callbacks.size());
        assertSame("retry replaced the service's permanently registered client", manager.registeredClient, manager.callback());
        assertEquals("same source registered duplicate death recipients", 1, manager.deaths.size());
        manager.registeredClient.bindApplication(new Bundle());
        assertTrue(NativeStellar.pingBinder());
        assertSame(manager.registeredClient.asBinder(), NativeStellar.getClientBinder());
        assertTrue(NativeStellar.checkSelfPermission());
        NativeStellar.requestPermission(42);
        NativeStellar.shouldShowRequestPermissionRationale();
        assertEquals("permission result sent to the first callback was lost", 1, manager.rationaleQueries);
    }

    @Test public void failedAttachRetryPreservesServiceRegisteredClient() throws Exception {
        Manager manager = new Manager();
        manager.duringAttach = () -> { throw new IllegalStateException("reply failed after client registration"); };
        manager.connect();
        assertNull(NativeStellar.getBinder());
        manager.duringAttach = null;

        manager.connect();

        assertTrue(NativeStellar.pingBinder());
        assertSame("alive source forgot its first callback after failed attach", manager.registeredClient, manager.callback());
        assertSame(manager.registeredClient.asBinder(), NativeStellar.getClientBinder());
        assertEquals(1, manager.deaths.size());
        assertTrue(NativeStellar.checkSelfPermission());
        NativeStellar.requestPermission(43);
        NativeStellar.shouldShowRequestPermissionRationale();
        assertEquals("authorization reply to the registered callback was discarded", 1, manager.rationaleQueries);
    }

    @Test public void revisitingAliveManagerPreservesItsRegisteredClient() throws Exception {
        Manager first = new Manager(); first.connect();
        Manager second = new Manager(); second.connect();

        first.connect();
        second.deaths.get(0).binderDied();

        assertSame(first.binder, NativeStellar.getBinder());
        assertTrue(NativeStellar.pingBinder());
        assertSame(first.registeredClient, first.callback());
        assertSame(first.registeredClient.asBinder(), NativeStellar.getClientBinder());
        assertEquals(1, first.deaths.size());
        assertTrue(NativeStellar.checkSelfPermission());
        NativeStellar.requestPermission(44);
        NativeStellar.shouldShowRequestPermissionRationale();
        assertEquals(1, first.rationaleQueries);
    }

    @Test public void concurrentDuplicateAttachUsesOneRegisteredClientAndDeathLease() throws Exception {
        Manager manager = new Manager();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.duringAttach = () -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("attach fixture timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        Thread attaching = new Thread(manager::connect);
        attaching.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            manager.connect();

            assertEquals("duplicate started another synchronous attach", 1, manager.callbacks.size());
            assertEquals("duplicate registered another death lease", 1, manager.deaths.size());
            release.countDown();
            attaching.join(2000);
            assertFalse(attaching.isAlive());
            assertTrue(NativeStellar.pingBinder());
            assertSame(manager.registeredClient.asBinder(), NativeStellar.getClientBinder());
        } finally {
            release.countDown();
            attaching.join(2000);
        }
    }

    @Test public void oldAttachCallbackCannotMarkReplacementReady() throws Exception {
        Manager old = new Manager(); old.attachImmediately = false; old.connect();
        Manager replacement = new Manager(); replacement.attachImmediately = false; replacement.connect();

        old.callback().bindApplication(new Bundle());

        assertFalse("replacement became ready from the old manager reply", NativeStellar.pingBinder());
        replacement.callback().bindApplication(new Bundle());
        assertTrue(NativeStellar.pingBinder());
    }

    @Test public void oldPermissionCallbackCannotRevokeReplacementPermissionCache() throws Exception {
        Manager old = new Manager(); old.connect();
        Manager replacement = new Manager(); replacement.connect();
        assertTrue(NativeStellar.checkSelfPermission());

        // Mock Bundle returns false: this is a late permission-denied result from the old source.
        old.callback().dispatchRequestPermissionResult(42, new Bundle());

        assertFalse(NativeStellar.shouldShowRequestPermissionRationale());
        assertEquals("old reply erased the replacement's granted permission", 0, replacement.rationaleQueries);
        replacement.callback().dispatchRequestPermissionResult(43, new Bundle());
        NativeStellar.shouldShowRequestPermissionRationale();
        assertEquals("current permission replies must still apply", 1, replacement.rationaleQueries);
    }

    @Test public void clearedConnectionIgnoresLateAttachAndKeepsClientBinderAvailable() throws Exception {
        Manager manager = new Manager(); manager.connect();
        IBinder client = NativeStellar.getClientBinder();
        NativeStellar.clearBinder();

        manager.callback().bindApplication(new Bundle());

        assertNull(NativeStellar.getBinder());
        assertFalse(NativeStellar.pingBinder());
        assertEquals("late attach repopulated cleared metadata", -1, NativeStellar.getServerUid());
        assertSame(client, NativeStellar.getClientBinder());
    }

    @Test public void latePermissionQueryCannotOverwriteReplacementCache() throws Exception {
        Manager old = new Manager(); old.permission = false;
        old.permissionEntered = new CountDownLatch(1);
        old.permissionRelease = new CountDownLatch(1);
        old.connect();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread query = new Thread(() -> result.set(NativeStellar.checkSelfPermission()));
        query.start();
        try {
            assertTrue(old.permissionEntered.await(2, TimeUnit.SECONDS));
            Manager replacement = new Manager(); replacement.connect();
            assertTrue(NativeStellar.checkSelfPermission());
            old.permissionRelease.countDown();
            query.join(2000);

            assertFalse("permission query did not finish", query.isAlive());
            assertEquals(Boolean.FALSE, result.get());
            NativeStellar.shouldShowRequestPermissionRationale();
            assertEquals("stale query changed the new connection's permission", 0, replacement.rationaleQueries);
        } finally {
            old.permissionRelease.countDown();
            query.join(2000);
        }
    }

    @Test public void oldAttachFailureCannotClearReplacementConnectedDuringAttach() {
        Manager replacement = new Manager();
        Manager old = new Manager();
        old.duringAttach = () -> {
            replacement.connect();
            throw new IllegalStateException("synthetic old attach failure");
        };

        old.connect();

        assertSame("old attach failure discarded its replacement", replacement.binder, NativeStellar.getBinder());
        assertTrue(NativeStellar.pingBinder());
    }

    @Test public void currentDeathClearsConnectionAndReadyDuplicateDoesNotReattach() {
        Manager manager = new Manager(); manager.connect(); manager.connect();
        assertEquals(1, manager.callbacks.size());

        manager.deaths.get(0).binderDied();

        assertNull(NativeStellar.getBinder());
        assertFalse(NativeStellar.pingBinder());
        assertNotNull(NativeStellar.getClientBinder());
    }
}
