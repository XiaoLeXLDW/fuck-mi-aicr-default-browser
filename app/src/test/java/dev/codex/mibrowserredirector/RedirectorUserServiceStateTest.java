package dev.codex.mibrowserredirector;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class RedirectorUserServiceStateTest {
    @Test(timeout = 20_000)
    public void stateReadsSurviveConcurrentDisable() throws Exception {
        RedirectorUserService service = new RedirectorUserService();
        Field controllerField = field("systemController");
        SystemActivityController controller = controllerFor(new Transport());
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(2);
        Thread[] readers = new Thread[2];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                started.countDown();
                try {
                    while (!done.get()) assertNotNull(service.getState());
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "service-state-reader-" + i);
            readers[i].setDaemon(true);
            readers[i].start();
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            // Restore only the transport fixture; exercise the real disable path on
            // every iteration, as a stop RPC can overlap an already-started read.
            for (int i = 0; i < 200_000 && failure.get() == null; i++) {
                controllerField.set(service, controller);
                service.disable();
            }
        } finally {
            done.set(true);
            for (Thread reader : readers) reader.join(2000);
            closeDispatcher(service);
        }
        for (Thread reader : readers) assertFalse("state reader did not finish", reader.isAlive());
        if (failure.get() != null) throw new AssertionError("state read raced with disable", failure.get());
    }

    @Test(timeout = 5000)
    public void stateReadDoesNotWaitForDisableHoldingTheServiceMonitor() throws Exception {
        RedirectorUserService service = new RedirectorUserService();
        Transport transport = new Transport();
        transport.block = true;
        field("systemController").set(service, controllerFor(transport));
        CountDownLatch readFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread stopper = new Thread(service::disable, "blocked-disable");
        Thread reader = new Thread(() -> {
            try { assertNotNull(service.getState()); }
            catch (Throwable error) { failure.set(error); }
            finally { readFinished.countDown(); }
        }, "independent-state-read");
        stopper.setDaemon(true);
        reader.setDaemon(true);
        try {
            stopper.start();
            assertTrue("disable did not reach the system transport",
                    transport.entered.await(2, TimeUnit.SECONDS));
            reader.start();
            assertTrue("state read must not wait on a blocked disable RPC",
                    readFinished.await(500, TimeUnit.MILLISECONDS));
        } finally {
            transport.release.countDown();
            stopper.join(2000);
            reader.join(2000);
            closeDispatcher(service);
        }
        assertFalse("disable did not finish", stopper.isAlive());
        assertFalse("state reader did not finish", reader.isAlive());
        if (failure.get() != null) throw new AssertionError("state read failed", failure.get());
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = RedirectorUserService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static SystemActivityController controllerFor(Transport transport)
            throws ReflectiveOperationException {
        Constructor<SystemActivityController> constructor = SystemActivityController.class
                .getDeclaredConstructor(Object.class, Method.class);
        constructor.setAccessible(true);
        return constructor.newInstance(transport, Transport.class.getMethod("set", Object.class));
    }

    private static void closeDispatcher(RedirectorUserService service) throws Exception {
        RedirectDispatcher dispatcher = (RedirectDispatcher) field("redirectDispatcher").get(service);
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
    }

    public static final class Transport {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean block;

        public void set(Object ignored) throws InterruptedException {
            if (block) {
                entered.countDown();
                release.await();
            }
        }
    }
}
