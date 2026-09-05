package dev.codex.mibrowserredirector;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static dev.codex.mibrowserredirector.RedirectDispatcher.Result.*;
import static dev.codex.mibrowserredirector.RedirectDispatcher.Submission.*;
import static org.junit.Assert.*;

public class RedirectDispatcherTest {
    private static final String URL = "https://example.test/private?token=not-for-logs";
    private final List<RedirectDispatcher> dispatchers = new ArrayList<>();
    private final List<FakeLaunch> launches = new ArrayList<>();
    private final BlockingQueue<RedirectDispatcher.Result> finished = new LinkedBlockingQueue<>();
    private final AtomicLong nanos = new AtomicLong();

    @After public void stopWorkers() throws Exception {
        for (FakeLaunch launch : launches) {
            launch.prepareGate.countDown(); launch.startGate.countDown(); launch.awaitGate.countDown();
        }
        for (RedirectDispatcher dispatcher : dispatchers) {
            dispatcher.close(); assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private FakeLaunch launch(boolean waiting) {
        FakeLaunch launch = new FakeLaunch(waiting); launches.add(launch); return launch;
    }

    private RedirectDispatcher dispatcher(int capacity, FakeLaunch... planned) {
        BlockingQueue<FakeLaunch> plan = new LinkedBlockingQueue<>();
        for (FakeLaunch launch : planned) plan.add(launch);
        RedirectDispatcher dispatcher = new RedirectDispatcher((target, url) -> {
            FakeLaunch launch = plan.remove();
            launch.target = target; launch.prepared.countDown(); launch.prepareGate.await();
            return launch;
        }, (config, url, result) -> finished.add(result), nanos::get,
                capacity, TimeUnit.MILLISECONDS.toNanos(1500));
        dispatchers.add(dispatcher); dispatcher.configure("browser.a", false); return dispatcher;
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("latch deadline", latch.await(2, TimeUnit.SECONDS));
    }

    private static RedirectDispatcher.Submission submit(RedirectDispatcher dispatcher, String url) {
        // REJECTED on brief gate contention is an intentional fail-open result. Retry
        // only in test setup where an empty slot is known to exist.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        RedirectDispatcher.Submission value;
        do {
            value = dispatcher.trySubmit(dispatcher.snapshot(), url);
            if (value != REJECTED) return value;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        return value;
    }

    private void result(RedirectDispatcher.Result expected) throws Exception {
        assertEquals(expected, finished.poll(2, TimeUnit.SECONDS));
    }

    @Test public void disableClearsQueuedWorkAndCancelsCommittedChild() throws Exception {
        FakeLaunch active = launch(true), sentinel = launch(false);
        RedirectDispatcher dispatcher = dispatcher(8, active, sentinel);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(active.started);
        assertEquals(ACCEPTED, submit(dispatcher, URL + "&queued"));
        dispatcher.disable(); await(active.closed);
        assertTrue(active.cancelled);
        assertEquals(REJECTED, dispatcher.trySubmit(dispatcher.snapshot(), URL));
        dispatcher.configure("browser.b", false);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
        assertEquals("browser.b", sentinel.target); // no queued browser.a launch consumed it
    }

    @Test public void invalidationAfterDequeueBeforeStartDiscardsOldWork() throws Exception {
        FakeLaunch old = launch(false), next = launch(false);
        old.prepareGate = new CountDownLatch(1);
        RedirectDispatcher dispatcher = dispatcher(8, old, next);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(old.prepared);
        dispatcher.disable(); old.prepareGate.countDown(); await(old.closed);
        assertEquals(1, old.started.getCount());
        dispatcher.configure("browser.b", false);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
        assertEquals("browser.b", next.target);
    }

    @Test public void sameUrlNewConfigRetriesWithoutWaitingForOldCompletion() throws Exception {
        FakeLaunch old = launch(true), next = launch(true);
        old.releaseOnCancel = false;
        RedirectDispatcher dispatcher = dispatcher(8, old, next);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(old.started);
        long generation = dispatcher.snapshot().generation;
        dispatcher.configure("browser.b", false);
        assertTrue(dispatcher.snapshot().generation > generation);
        assertEquals(ACCEPTED, submit(dispatcher, URL));
        old.result = FAILED; old.awaitGate.countDown(); await(next.started);
        assertEquals(DUPLICATE, submit(dispatcher, URL)); // old failure did not delete new key
        next.awaitGate.countDown(); result(SUCCEEDED);
        assertEquals("browser.b", next.target);
    }

    @Test public void observeModeAndSameTargetReconfigureInvalidateSnapshot() throws Exception {
        FakeLaunch next = launch(false);
        RedirectDispatcher dispatcher = dispatcher(8, next);
        RedirectDispatcher.Snapshot old = dispatcher.snapshot();
        dispatcher.configure("browser.a", true);
        assertEquals(REJECTED, dispatcher.trySubmit(dispatcher.snapshot(), URL));
        dispatcher.configure("browser.a", false);
        assertEquals(REJECTED, dispatcher.trySubmit(old, URL));
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void startGateSerializesDisableButCallbackNeverWaitsForIt() throws Exception {
        FakeLaunch active = launch(true);
        active.startGate = new CountDownLatch(1);
        RedirectDispatcher dispatcher = dispatcher(8, active);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(active.started);
        CountDownLatch disabling = new CountDownLatch(1), disabled = new CountDownLatch(1);
        Thread stopper = new Thread(() -> { disabling.countDown(); dispatcher.disable(); disabled.countDown(); });
        stopper.start(); await(disabling);
        assertEquals(1, disabled.getCount()); // start has committed but not yet returned
        BlockingQueue<RedirectDispatcher.Submission> callback = new LinkedBlockingQueue<>();
        Thread caller = new Thread(() -> callback.add(dispatcher.trySubmit(dispatcher.snapshot(), URL)));
        caller.start();
        assertEquals(REJECTED, callback.poll(500, TimeUnit.MILLISECONDS));
        active.startGate.countDown(); await(disabled); await(active.closed);
        caller.join(1000); stopper.join(1000);
        assertEquals(REJECTED, dispatcher.trySubmit(dispatcher.snapshot(), URL + "&after"));
    }

    @Test public void failureAllowsImmediateRetry() throws Exception {
        FakeLaunch first = launch(false), second = launch(false); first.result = FAILED;
        RedirectDispatcher dispatcher = dispatcher(8, first, second);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(FAILED);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void timeoutAllowsImmediateRetry() throws Exception {
        FakeLaunch first = launch(false), second = launch(false); first.result = TIMED_OUT;
        RedirectDispatcher dispatcher = dispatcher(8, first, second);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(TIMED_OUT);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void inFlightDoesNotExpireAndSuccessWindowStartsAtCompletion() throws Exception {
        FakeLaunch first = launch(true), second = launch(false);
        RedirectDispatcher dispatcher = dispatcher(8, first, second);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(first.started);
        nanos.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertEquals(DUPLICATE, submit(dispatcher, URL));
        first.awaitGate.countDown(); result(SUCCEEDED);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1499));
        assertEquals(DUPLICATE, submit(dispatcher, URL));
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void nanoTimeWrapStillExpiresSuccess() throws Exception {
        nanos.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1));
        RedirectDispatcher dispatcher = dispatcher(8, launch(false), launch(false));
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void boundedQueueRejectDoesNotReserveDedupeKey() throws Exception {
        FakeLaunch first = launch(true), second = launch(true), third = launch(false);
        RedirectDispatcher dispatcher = dispatcher(1, first, second, third);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(first.started);
        assertEquals(ACCEPTED, submit(dispatcher, URL + "2"));
        assertEquals(REJECTED, dispatcher.trySubmit(dispatcher.snapshot(), URL + "3"));
        first.awaitGate.countDown(); result(SUCCEEDED); await(second.started);
        assertEquals(ACCEPTED, submit(dispatcher, URL + "3"));
        second.awaitGate.countDown(); result(SUCCEEDED); result(SUCCEEDED);
    }

    @Test public void startExceptionClosesHandleAndAllowsRetry() throws Exception {
        FakeLaunch first = launch(false), second = launch(false);
        first.startException = new IOException("do not log " + URL);
        RedirectDispatcher dispatcher = dispatcher(8, first, second);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(FAILED); await(first.closed);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void interruptedLaunchIsCleanedAndWorkerCanRunNextTask() throws Exception {
        FakeLaunch first = launch(false), second = launch(false);
        first.interruptAwait = true;
        RedirectDispatcher dispatcher = dispatcher(8, first, second);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(CANCELLED); await(first.closed);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    @Test public void closeCancelsActiveDiscardsQueueAndNeverRestarts() throws Exception {
        FakeLaunch active = launch(true);
        RedirectDispatcher dispatcher = dispatcher(8, active);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); await(active.started);
        assertEquals(ACCEPTED, submit(dispatcher, URL + "queued"));
        dispatcher.close(); assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(active.cancelled); await(active.closed);
        dispatcher.configure("browser.b", false);
        assertEquals(REJECTED, dispatcher.trySubmit(dispatcher.snapshot(), URL));
    }

    @Test public void acceptedEventAlwaysPrecedesEvenInstantFailure() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        FakeLaunch first = launch(false); first.result = FAILED;
        RedirectDispatcher dispatcher = new RedirectDispatcher((target, url) -> first,
                new RedirectDispatcher.Listener() {
                    @Override public void configured(RedirectDispatcher.Snapshot config) {
                        events.add("configured");
                    }
                    @Override public void accepted(RedirectDispatcher.Snapshot config, String url) {
                        events.add("accepted");
                    }
                    @Override public void finished(RedirectDispatcher.Snapshot config, String url,
                            RedirectDispatcher.Result result) { events.add(result.name()); }
                });
        dispatchers.add(dispatcher); dispatcher.configure("browser.a", false);
        assertEquals(ACCEPTED, submit(dispatcher, URL));
        assertEquals("configured", events.poll(2, TimeUnit.SECONDS));
        assertEquals("accepted", events.poll(2, TimeUnit.SECONDS));
        assertEquals("FAILED", events.poll(2, TimeUnit.SECONDS));
        assertTrue(events.isEmpty());
    }

    @Test public void staleCallbackDiagnosticCannotOverwriteNewConfiguration() throws Exception {
        RedirectDispatcher dispatcher = dispatcher(8, launch(false));
        RedirectDispatcher.Snapshot old = dispatcher.snapshot();
        String[] message = {"initial"};
        dispatcher.tryRecord(old, () -> message[0] = "current diagnostic");
        assertEquals("current diagnostic", message[0]);
        dispatcher.disable(); message[0] = "disabled";
        dispatcher.tryRecord(old, () -> message[0] = "stale callback");
        assertEquals("disabled", message[0]);
        dispatcher.configure("browser.b", false); message[0] = "new config";
        dispatcher.tryRecord(old, () -> message[0] = "stale callback");
        assertEquals("new config", message[0]);
    }

    @Test public void preparationFailureReleasesReservationAndWorkerSurvives() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        FakeLaunch second = launch(false);
        RedirectDispatcher dispatcher = new RedirectDispatcher((target, url) -> {
            if (attempts.getAndIncrement() == 0) throw new IOException("do not log " + URL);
            return second;
        }, (config, url, result) -> finished.add(result));
        dispatchers.add(dispatcher); dispatcher.configure("browser.a", false);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(FAILED);
        assertEquals(ACCEPTED, submit(dispatcher, URL)); result(SUCCEEDED);
    }

    private static final class FakeLaunch implements RedirectDispatcher.Launch {
        final CountDownLatch prepared = new CountDownLatch(1), started = new CountDownLatch(1),
                closed = new CountDownLatch(1);
        CountDownLatch prepareGate = new CountDownLatch(0), startGate = new CountDownLatch(0), awaitGate;
        volatile String target;
        volatile boolean cancelled, interruptAwait;
        boolean releaseOnCancel = true;
        IOException startException;
        volatile RedirectDispatcher.Result result = SUCCEEDED;
        FakeLaunch(boolean waiting) { awaitGate = new CountDownLatch(waiting ? 1 : 0); }
        @Override public void start() throws Exception {
            started.countDown(); startGate.await();
            if (startException != null) throw startException;
        }
        @Override public RedirectDispatcher.Result await() throws InterruptedException {
            if (interruptAwait) throw new InterruptedException();
            awaitGate.await(); return cancelled ? CANCELLED : result;
        }
        @Override public void cancel() { cancelled = true; if (releaseOnCancel) awaitGate.countDown(); }
        @Override public void close() { closed.countDown(); }
    }
}
