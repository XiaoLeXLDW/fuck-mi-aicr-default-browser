package dev.codex.mibrowserredirector.privilege;

import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual adapter sessions and connection callbacks; no Shizuku server or device is contacted. */
public class ShizukuProtocolRejectionTest {
    private static IBinder binder(AtomicBoolean alive) {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isBinderAlive")) return alive.get();
                    throw new AssertionError("only local death knowledge is allowed: " + method.getName());
                });
    }

    private static PrivilegeRuntime.ServiceSession session(IBinder manager, IBinder service) throws Exception {
        ShizukuBackendAdapter adapter = new ShizukuBackendAdapter(null, new Handler(),
                ShizukuProtocolRejectionTest.class, "fixture", "protocol-fixture", 1);
        Class<?> type = Class.forName(ShizukuBackendAdapter.class.getName() + "$ShizukuSession");
        Constructor<?> constructor = type.getDeclaredConstructor(ShizukuBackendAdapter.class,
                PrivilegeRuntime.ServiceCallback.class, IBinder.class);
        constructor.setAccessible(true);
        PrivilegeRuntime.ServiceSession session = (PrivilegeRuntime.ServiceSession) constructor.newInstance(adapter, null, manager);
        connect(session, service);
        return session;
    }

    private static void connect(PrivilegeRuntime.ServiceSession session, IBinder service) throws Exception {
        Field field = session.getClass().getDeclaredField("connection"); field.setAccessible(true);
        ((ServiceConnection) field.get(session)).onServiceConnected(null, service);
    }

    @Test public void rejectedProtocolSurvivesFailedCleanupAndNewActivityAdapter() throws Exception {
        IBinder manager = binder(new AtomicBoolean(true)), service = binder(new AtomicBoolean(true));
        PrivilegeRuntime.ServiceSession first = session(manager, service);
        first.rejectServiceCommands(service); // No death confirmation: failed cleanup must retain rejection.
        PrivilegeRuntime.ServiceSession next = session(manager, service);
        assertFalse("new adapter forgot protocol rejection of the same live service", next.allowsServiceCommands(service));
        assertNull("Main must retain the captured Binder, not read a mutable adapter field", next.cleanupBinder());
    }

    @Test public void rejectionDoesNotContaminateAReplacementService() throws Exception {
        IBinder manager = binder(new AtomicBoolean(true)), firstBinder = binder(new AtomicBoolean(true));
        session(manager, firstBinder).rejectServiceCommands(firstBinder);
        IBinder replacement = binder(new AtomicBoolean(true));
        assertTrue(session(manager, replacement).allowsServiceCommands(replacement));
    }

    @Test public void rejectionIsScopedToTheSourceManager() throws Exception {
        IBinder firstManager = binder(new AtomicBoolean(true)), service = binder(new AtomicBoolean(true));
        session(firstManager, service).rejectServiceCommands(service);
        assertTrue(session(binder(new AtomicBoolean(true)), service).allowsServiceCommands(service));
    }

    @Test public void lateConnectionMustNotRetargetRejectionOfCapturedBinder() throws Exception {
        IBinder manager = binder(new AtomicBoolean(true)), a = binder(new AtomicBoolean(true)), b = binder(new AtomicBoolean(true));
        PrivilegeRuntime.ServiceSession verifyingA = session(manager, a);
        connect(verifyingA, b); // B arrives before A's verification result is processed.
        verifyingA.rejectServiceCommands(a);
        assertFalse("A's rejection was assigned to B", verifyingA.allowsServiceCommands(a));
        assertTrue("same-session B inherited A's rejection", verifyingA.allowsServiceCommands(b));
        assertFalse("new session forgot A", session(manager, a).allowsServiceCommands(a));
        assertTrue("B inherited rejection meant only for A", session(manager, b).allowsServiceCommands(b));
    }
}
