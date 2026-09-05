package dev.codex.mibrowserredirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.After;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class UserServiceStopperTest {
    @After
    public void noWorkerIsLeftBlockedByTheTest() throws Exception {
        awaitIdle();
    }

    @Test
    public void totalDeadlineIncludesBlockingDisable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
            @Override
            public String disable() {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Model a synchronous Binder call that does not cooperate with cancellation.
                    }
                }
                return "已停用";
            }

            @Override
            public void destroy() { }

            @Override
            public boolean isAlive() { return false; }
        };
        FutureTask<UserServiceStopper.Result> task = new FutureTask<>(
                () -> UserServiceStopper.stop(endpoint, () -> { }));
        Thread supervisor = new Thread(task, "stopper-regression-caller");
        supervisor.setDaemon(true);
        supervisor.start();
        try {
            assertTrue("disable must start", entered.await(1, TimeUnit.SECONDS));
            try {
                assertFalse("an unfinished disable cannot confirm stop",
                        task.get(4500, TimeUnit.MILLISECONDS).stopped);
            } catch (TimeoutException failure) {
                throw new AssertionError("total stop deadline missing: disable still blocks after 4.5s",
                        failure);
            }
        } finally {
            release.countDown();
            supervisor.join(2000);
        }
    }

    @Test
    public void disablesRemovesAndWaitsForGracefulDeathInOrder() {
        List<String> calls = new ArrayList<>();
        SequenceEndpoint endpoint = new SequenceEndpoint(calls, true, true, false);

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> calls.add("remove"),
                4,
                0L,
                ignored -> calls.add("wait")
        );

        assertTrue(result.stopped);
        assertEquals(
                Arrays.asList("disable", "remove", "alive", "wait", "alive", "wait", "alive"),
                calls
        );
    }

    @Test
    public void neverClaimsSuccessWhileBinderRemainsAlive() {
        List<String> calls = new ArrayList<>();
        SequenceEndpoint endpoint = new SequenceEndpoint(calls, true, true, true, true);

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> calls.add("remove"),
                3,
                0L,
                ignored -> calls.add("wait")
        );

        assertFalse(result.stopped);
        assertTrue(result.detail.contains("仍存活"));
        assertTrue(calls.contains("destroy"));
    }

    @Test
    public void deadBinderIsSuccessEvenWhenDisableCannotReply() {
        UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
            @Override
            public String disable() throws Exception {
                throw new Exception("binder closed");
            }

            @Override
            public void destroy() throws Exception {
                throw new AssertionError("dead Binder must not need forced destroy");
            }

            @Override
            public boolean isAlive() {
                return false;
            }
        };

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> { },
                1,
                0L,
                ignored -> { }
        );

        assertTrue(result.stopped);
        assertTrue(result.detail.contains("已确认"));
    }

    @Test
    public void blockingDisableTimesOutWithoutLaterRemoveOrDestroy() throws Exception {
        assertBlockedStageIsBounded("disable");
    }

    @Test
    public void blockingRemoveTimesOutWithoutLaterAliveOrDestroy() throws Exception {
        assertBlockedStageIsBounded("remove");
    }

    @Test
    public void blockingAliveCannotReportDeathOrDestroyAfterDeadline() throws Exception {
        assertBlockedStageIsBounded("alive");
    }

    @Test
    public void blockingDestroyCannotReportSuccessEvenWhenBinderDied() throws Exception {
        assertBlockedStageIsBounded("destroy");
    }

    private static void assertBlockedStageIsBounded(String stage) throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint(stage);
        FutureTask<UserServiceStopper.Result> task = startStop(endpoint, 150L);
        try {
            assertTrue(stage + " must start", endpoint.entered.await(1, TimeUnit.SECONDS));
            UserServiceStopper.Result result = task.get(2, TimeUnit.SECONDS);
            assertFalse("blocked " + stage + " cannot confirm stop", result.stopped);
            assertTrue(result.detail, result.detail.contains("150 毫秒"));
            assertTrue("actual call, not Future state, owns the guard",
                    UserServiceStopper.hasPendingCalls());
            assertTrue("worker must be daemon", endpoint.worker.isDaemon());
            assertTrue("cancellation attempted but RPC ignores it",
                    endpoint.interrupted.await(1, TimeUnit.SECONDS));
            List<String> callsAtTimeout = new ArrayList<>(endpoint.calls);
            endpoint.release.countDown();
            awaitIdle();
            assertEquals("no subsequent stage after timed-out " + stage,
                    callsAtTimeout, endpoint.calls);
            assertFalse("late return must not mutate the published timeout", result.stopped);
        } finally {
            endpoint.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            awaitIdle();
        }
    }

    @Test
    public void nonCooperativeCallCapsRepeatedAttemptsAcrossEndpoints() throws Exception {
        BlockingEndpoint first = new BlockingEndpoint("disable");
        FutureTask<UserServiceStopper.Result> task = startStop(first, 100L);
        try {
            assertTrue(first.entered.await(1, TimeUnit.SECONDS));
            assertFalse(task.get(2, TimeUnit.SECONDS).stopped);
            assertTrue(first.interrupted.await(1, TimeUnit.SECONDS));
            List<String> rejectedCalls = new CopyOnWriteArrayList<>();
            SequenceEndpoint other = new SequenceEndpoint(rejectedCalls, false);
            for (int attempt = 0; attempt < 100; attempt++) {
                UserServiceStopper.Result rejected = UserServiceStopper.stop(
                        other, () -> rejectedCalls.add("remove"), 100L);
                assertFalse(rejected.stopped);
                assertTrue(rejected.detail, rejected.detail.contains("前一次"));
                assertTrue(UserServiceStopper.hasPendingCalls());
            }
            assertTrue("retries never create more admitted RPCs", rejectedCalls.isEmpty());
            assertEquals(Arrays.asList("disable"), first.calls);
            long workerCount = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive()
                            && thread.getName().equals("user-service-stop-rpc")).count();
            assertEquals("global pool never replaces an uninterruptible worker", 1L, workerCount);
        } finally {
            first.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            awaitIdle();
        }
    }

    @Test
    public void lateDisableExceptionDoesNotStartRemoval() throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint("disable");
        endpoint.failAfterRelease = true;
        FutureTask<UserServiceStopper.Result> task = startStop(endpoint, 100L);
        try {
            assertTrue(endpoint.entered.await(1, TimeUnit.SECONDS));
            assertFalse(task.get(2, TimeUnit.SECONDS).stopped);
        } finally {
            endpoint.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            awaitIdle();
        }
        assertEquals(Arrays.asList("disable"), endpoint.calls);
    }

    @Test
    public void supervisorInterruptionRetainsGuardUntilRpcActuallyReturns() throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint("disable");
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        FutureTask<UserServiceStopper.Result> task = new FutureTask<>(() -> {
            UserServiceStopper.Result result = UserServiceStopper.stop(endpoint, endpoint::remove);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            return result;
        });
        Thread supervisor = startCaller(task);
        try {
            assertTrue(endpoint.entered.await(1, TimeUnit.SECONDS));
            supervisor.interrupt();
            assertFalse(task.get(2, TimeUnit.SECONDS).stopped);
            assertTrue(interruptPreserved.get());
            assertTrue(UserServiceStopper.hasPendingCalls());
        } finally {
            endpoint.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            awaitIdle();
        }
        assertEquals(Arrays.asList("disable"), endpoint.calls);
    }

    @Test
    public void preInterruptedSupervisorDoesNotStartRpc() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        FutureTask<UserServiceStopper.Result> task = new FutureTask<>(() -> {
            Thread.currentThread().interrupt();
            UserServiceStopper.Result result = UserServiceStopper.stop(
                    new SequenceEndpoint(calls, false), () -> calls.add("remove"));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            return result;
        });
        startCaller(task);
        assertFalse(task.get(2, TimeUnit.SECONDS).stopped);
        assertTrue(interruptPreserved.get());
        assertTrue(calls.isEmpty());
        assertFalse(UserServiceStopper.hasPendingCalls());
    }

    @Test
    public void interruptedPollNeverFallsThroughToDestroy() {
        List<String> calls = new CopyOnWriteArrayList<>();
        UserServiceStopper.Result result = UserServiceStopper.stop(
                new SequenceEndpoint(calls, true), () -> calls.add("remove"),
                500L, 4, 1L, ignored -> { throw new InterruptedException("cancelled"); });
        assertFalse(result.stopped);
        assertEquals(Arrays.asList("disable", "remove", "alive"), calls);
    }

    @Test
    public void aliveExceptionIsUnconfirmedNotAssumedDeath() {
        List<String> calls = new CopyOnWriteArrayList<>();
        UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
            @Override public String disable() { calls.add("disable"); return null; }
            @Override public void destroy() { calls.add("destroy"); }
            @Override public boolean isAlive() throws Exception {
                calls.add("alive");
                throw new Exception("probe failed");
            }
        };
        UserServiceStopper.Result result = UserServiceStopper.stop(endpoint,
                () -> calls.add("remove"), 500L);
        assertFalse(result.stopped);
        assertTrue(result.detail, result.detail.contains("probe failed"));
        assertEquals(Arrays.asList("disable", "remove", "alive"), calls);
        assertFalse(UserServiceStopper.hasPendingCalls());
    }

    @Test
    public void sharedMonotonicBudgetIsNotResetBetweenCalls() {
        // Each individual stage takes <100ms, but together they exceed the ONE 100ms budget.
        // Start close to nanoTime rollover to verify elapsed subtraction, not absolute ordering.
        AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(50));
        List<String> calls = new CopyOnWriteArrayList<>();
        UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
            @Override public String disable() {
                calls.add("disable");
                nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
                return null;
            }
            @Override public void destroy() { calls.add("destroy"); }
            @Override public boolean isAlive() {
                calls.add("alive");
                nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
                return false;
            }
        };
        UserServiceStopper.Result result = UserServiceStopper.stop(endpoint, () -> {
            calls.add("remove");
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
        }, 100L, 4, 1L, ignored -> { }, nanos::get);
        assertFalse("late false is not confirmation within deadline", result.stopped);
        assertTrue(result.detail, result.detail.contains("总截止时间"));
        assertEquals(Arrays.asList("disable", "remove", "alive"), calls);
    }

    @Test
    public void expiredBudgetIsCheckedBeforeNextDestructiveStageWithoutSupervisorWakeup() {
        for (String expensiveStage : Arrays.asList("disable", "remove", "alive", "wait")) {
            AtomicLong nanos = new AtomicLong();
            List<String> calls = new CopyOnWriteArrayList<>();
            UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
                void advance(String stage) {
                    calls.add(stage);
                    if (expensiveStage.equals(stage)) nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(101));
                }
                @Override public String disable() { advance("disable"); return null; }
                @Override public void destroy() { advance("destroy"); }
                @Override public boolean isAlive() { advance("alive"); return true; }
            };
            UserServiceStopper.Result result = UserServiceStopper.stop(endpoint, () -> {
                calls.add("remove");
                if (expensiveStage.equals("remove")) nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(101));
            }, 100L, 4, 1L, ignored -> {
                calls.add("wait");
                if (expensiveStage.equals("wait")) nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(101));
            }, nanos::get);
            assertFalse(result.stopped);
            assertEquals(expensiveStage, calls.get(calls.size() - 1));
            assertFalse("must not reach destroy after " + expensiveStage, calls.contains("destroy"));
        }
    }

    @Test
    public void deathAfterDestroyIsConfirmedOnlyAfterDestroyReturned() {
        List<String> calls = new CopyOnWriteArrayList<>();
        SequenceEndpoint endpoint = new SequenceEndpoint(calls, true, true, false);
        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint, () -> calls.add("remove"), 500L, 2, 0L, ignored -> calls.add("wait"));
        assertTrue(result.stopped);
        assertEquals(Arrays.asList("disable", "remove", "alive", "wait", "alive", "destroy", "alive"), calls);
        assertFalse(UserServiceStopper.hasPendingCalls());
    }

    @Test
    public void completedWorkerCanBeReusedWithoutRetryOrInheritedInterruption() throws Exception {
        BlockingEndpoint blocked = new BlockingEndpoint("disable");
        FutureTask<UserServiceStopper.Result> task = startStop(blocked, 100L);
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            assertFalse(task.get(2, TimeUnit.SECONDS).stopped);
        } finally {
            blocked.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            awaitIdle();
        }
        for (int attempt = 0; attempt < 500; attempt++) {
            UserServiceStopper.Result result = UserServiceStopper.stop(
                    new SequenceEndpoint(new ArrayList<>(), false), () -> { }, 500L);
            assertTrue("completed worker must accept the next operation: " + result.detail,
                    result.stopped);
            assertFalse(UserServiceStopper.hasPendingCalls());
        }
    }

    private static FutureTask<UserServiceStopper.Result> startStop(BlockingEndpoint endpoint,
            long timeoutMillis) {
        FutureTask<UserServiceStopper.Result> task = new FutureTask<>(() -> UserServiceStopper.stop(
                endpoint, endpoint::remove, timeoutMillis, 2, 0L, ignored -> { }));
        startCaller(task);
        return task;
    }

    private static Thread startCaller(FutureTask<UserServiceStopper.Result> task) {
        Thread caller = new Thread(task, "stopper-test-supervisor");
        caller.setDaemon(true);
        caller.start();
        return caller;
    }

    private static void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (UserServiceStopper.hasPendingCalls() && System.nanoTime() < deadline) Thread.sleep(1);
        assertFalse("test must release its noncooperative RPC", UserServiceStopper.hasPendingCalls());
    }

    private static final class BlockingEndpoint implements UserServiceStopper.Endpoint {
        final String blockedStage;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Thread worker;
        boolean failAfterRelease;
        boolean destroyed;

        BlockingEndpoint(String blockedStage) { this.blockedStage = blockedStage; }

        private void call(String stage) throws Exception {
            calls.add(stage);
            if (!stage.equals(blockedStage)) return;
            worker = Thread.currentThread();
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
            if (failAfterRelease) throw new Exception("late failure");
        }

        @Override public String disable() throws Exception { call("disable"); return "已停用"; }
        void remove() throws Exception { call("remove"); }
        @Override public void destroy() throws Exception {
            // A sent destroy may already have killed Binder while its transaction is still blocked.
            destroyed = true;
            call("destroy");
        }
        @Override public boolean isAlive() throws Exception {
            call("alive");
            return blockedStage.equals("destroy") && !destroyed;
        }
    }

    private static final class SequenceEndpoint implements UserServiceStopper.Endpoint {
        private final List<String> calls;
        private final boolean[] states;
        private int stateIndex;

        SequenceEndpoint(List<String> calls, boolean... states) {
            this.calls = calls;
            this.states = states;
        }

        @Override
        public String disable() {
            calls.add("disable");
            return "已停用";
        }

        @Override
        public void destroy() {
            calls.add("destroy");
        }

        @Override
        public boolean isAlive() {
            calls.add("alive");
            int index = Math.min(stateIndex, states.length - 1);
            stateIndex++;
            return states[index];
        }
    }
}
