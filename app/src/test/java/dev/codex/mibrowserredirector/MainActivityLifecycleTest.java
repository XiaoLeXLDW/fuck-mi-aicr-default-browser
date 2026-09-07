package dev.codex.mibrowserredirector;

import android.content.SharedPreferences;
import android.widget.TextView;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime;
import java.lang.reflect.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Activity callback methods with AGP's mock Android runtime; not a rendered UI test. */
public class MainActivityLifecycleTest {
    private static class Session implements PrivilegeRuntime.ServiceSession {
        private volatile boolean allowed = true;
        public boolean allowsServiceCommands(android.os.IBinder candidate) { return allowed; }
        public void rejectServiceCommands(android.os.IBinder rejected) { allowed = false; }
        public PrivilegeRuntime.BackendId backendId() { return PrivilegeRuntime.BackendId.STELLAR; }
        public void remove() { }
        public void detach() { }
        public boolean isCurrent() { return true; }
    }
    private static MainActivity activity() throws Exception {
        MainActivity activity = new MainActivity();
        set(activity, "statusText", new TextView(null));
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> method.getReturnType() == SharedPreferences.Editor.class ? proxy
                        : method.getName().equals("commit") ? true : null);
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> method.getName().equals("getBoolean") ? true
                        : method.getName().equals("edit") ? editor : null);
        set(activity, "preferences", preferences);
        return activity;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    @Test public void disconnectDuringReadMustAllowReadOnReplacementSession() throws Exception {
        MainActivity activity = activity(); Session old = new Session();
        set(activity, "serviceSession", old); set(activity, "stateReadPending", true);
        PrivilegeRuntime.ServiceCallback callback = (PrivilegeRuntime.ServiceCallback) get(activity, "userServiceCallback");
        callback.onServiceDisconnected(old);
        assertFalse("disconnect left the old read permanently pending", (Boolean) get(activity, "stateReadPending"));
        Session replacement = new Session(); set(activity, "serviceSession", replacement);
        IRedirectorService remote = (IRedirectorService) Proxy.newProxyInstance(
                IRedirectorService.class.getClassLoader(), new Class<?>[]{IRedirectorService.class},
                (proxy, method, args) -> null);
        set(activity, "remoteService", remote);
        invoke(activity, "refreshRemoteState");
        assertTrue("replacement read was not admitted", (Boolean) get(activity, "stateReadPending"));
        invoke(activity, "clearPendingActions");
    }
    @Test public void clearingPendingActionsMustAlsoCancelReadOwnership() throws Exception {
        MainActivity activity = activity(); set(activity, "stateReadPending", true);
        invoke(activity, "clearPendingActions");
        assertFalse("cancel invalidated token but left pending flag", (Boolean) get(activity, "stateReadPending"));
    }

    @Test public void rejectedIdentityNeverInvokesBusinessDisableOrDestroy() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        android.os.IBinder binder = (android.os.IBinder) Proxy.newProxyInstance(
                android.os.IBinder.class.getClassLoader(), new Class<?>[]{android.os.IBinder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isBinderAlive") || method.getName().equals("pingBinder")) return alive.get();
                    throw new AssertionError("rejected Binder transaction: " + method.getName());
                });
        IRedirectorService rejected = (IRedirectorService) Proxy.newProxyInstance(
                IRedirectorService.class.getClassLoader(), new Class<?>[]{IRedirectorService.class},
                (proxy, method, args) -> { throw new AssertionError("rejected business call: " + method.getName()); });
        Session session = new Session() {
            @Override public boolean allowsServiceCommands(android.os.IBinder candidate) { return false; }
            @Override public android.os.IBinder cleanupBinder() { return binder; }
            @Override public void remove() { alive.set(false); }
        };
        Method method = MainActivity.class.getDeclaredMethod("performStop", IRedirectorService.class,
                android.os.IBinder.class, PrivilegeRuntime.ServiceSession.class, long.class);
        method.setAccessible(true);
        UserServiceStopper.Result result = (UserServiceStopper.Result) method.invoke(activity(), rejected, binder, session, 400L);
        assertTrue(result.detail, result.stopped);
    }

    @Test public void callbacksDuringStopMustNotDiscardIdentityOrRepopulateEndpoint() throws Exception {
        MainActivity activity = activity(); Session session = new Session();
        set(activity, "serviceSession", session); set(activity, "stopWorkerRunning", true);
        PrivilegeRuntime.ServiceCallback callback = (PrivilegeRuntime.ServiceCallback) get(activity, "userServiceCallback");
        android.os.IBinder binder = (android.os.IBinder) Proxy.newProxyInstance(
                android.os.IBinder.class.getClassLoader(), new Class<?>[]{android.os.IBinder.class},
                (proxy, method, args) -> null);
        callback.onServiceConnected(session, binder);
        assertNull("late connect repopulated an endpoint being stopped", get(activity, "remoteService"));
        callback.onServiceDisconnected(session);
        assertSame("disconnect discarded cleanup owner before confirmation", session, get(activity, "serviceSession"));
    }

    @Test public void failedProtocolVerificationRevokesBusinessCallsBeforeCleanup() throws Exception {
        assertProtocolCleanup(false);
    }

    @Test public void timedOutProtocolVerificationRevokesBusinessCallsBeforeCleanup() throws Exception {
        assertProtocolCleanup(true);
    }

    private static void assertProtocolCleanup(boolean timeout) throws Exception {
        MainActivity activity = activity();
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger businessCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch confirmed = new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder binder = (android.os.IBinder) Proxy.newProxyInstance(
                android.os.IBinder.class.getClassLoader(), new Class<?>[]{android.os.IBinder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isBinderAlive") || method.getName().equals("pingBinder")) return alive.get();
                    businessCalls.incrementAndGet();
                    return method.getReturnType() == boolean.class ? false : null;
                });
        IRedirectorService candidate = (IRedirectorService) Proxy.newProxyInstance(
                IRedirectorService.class.getClassLoader(), new Class<?>[]{IRedirectorService.class},
                (proxy, method, args) -> { businessCalls.incrementAndGet(); return "must not call rejected endpoint"; });
        Session session = new Session() {
            @Override public android.os.IBinder cleanupBinder() { return binder; }
            @Override public void remove() { alive.set(false); }
            @Override public void onStopConfirmed() { confirmed.countDown(); }
        };
        set(activity, "serviceSession", session);
        set(activity, "remoteService", candidate);
        set(activity, "remoteBinder", binder);
        invoke(activity, "claimOperation");
        int epoch = (Integer) get(activity, "operationEpoch");
        Method verify = MainActivity.class.getDeclaredMethod("finishServiceVerification", int.class,
                PrivilegeRuntime.ServiceSession.class, IRedirectorService.class, android.os.IBinder.class,
                int.class, int.class, String.class);
        verify.setAccessible(true);
        try {
            if (timeout) {
                Method expire = MainActivity.class.getDeclaredMethod("finishRpcTimeout", int.class, int.class,
                        IRedirectorService.class, android.os.IBinder.class, PrivilegeRuntime.ServiceSession.class,
                        boolean.class, String.class);
                expire.setAccessible(true);
                expire.invoke(activity, epoch, (Integer) get(activity, "rpcToken"), candidate, binder,
                        session, true, "injected protocol timeout");
            } else {
                verify.invoke(activity, epoch, session, candidate, binder, -1, -1, "injected protocol read failure");
            }
            assertTrue("source-manager cleanup did not complete", confirmed.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse("protocol failure did not revoke business permission", session.allowsServiceCommands(binder));
            assertEquals("business RPC reached a protocol-rejected service", 0, businessCalls.get());
        } finally {
            Method end = MainActivity.class.getDeclaredMethod("endGlobalStop", int.class);
            end.setAccessible(true); end.invoke(activity, epoch);
        }
    }
}
