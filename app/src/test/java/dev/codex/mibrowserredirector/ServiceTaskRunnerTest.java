package dev.codex.mibrowserredirector;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServiceTaskRunnerTest {
    @Test
    public void stopRunsWhileStatusOrEnableRpcIsBlocked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        try (ServiceTaskRunner runner = new ServiceTaskRunner()) {
            try {
                runner.executeIo(() -> block(started, release));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                runner.executeStop(stopped::countDown);
                assertTrue("stop must not wait behind a blocked state RPC", stopped.await(300, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void fullIoQueueStillLeavesStopAvailable() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        try (ServiceTaskRunner runner = new ServiceTaskRunner()) {
            try {
                runner.executeIo(() -> block(started, release));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                for (int i = 0; i < 8; i++) runner.executeIo(() -> { });
                try {
                    runner.executeIo(() -> { });
                    fail("IO queue must be bounded");
                } catch (RejectedExecutionException expected) {
                    // The stop lane must remain independent of the saturated read lane.
                }
                runner.executeStop(stopped::countDown);
                assertTrue(stopped.await(300, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private static void block(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
