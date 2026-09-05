package dev.codex.mibrowserredirector;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static dev.codex.mibrowserredirector.RedirectDispatcher.Result.*;
import static org.junit.Assert.*;

public class AmProcessLauncherTest {
    private static final String URL = "https://example.test/private?token=not-for-logs";

    private static RedirectDispatcher.Launch launch(FakeProcess process) {
        return new AmProcessLauncher(command -> process, System::nanoTime,
                TimeUnit.SECONDS.toNanos(2), 100).prepare("browser.a", URL);
    }

    private static RedirectDispatcher.Result run(FakeProcess process) throws Exception {
        RedirectDispatcher.Launch launch = launch(process);
        try { launch.start(); return launch.await(); }
        finally { launch.close(); }
    }

    @Test public void commandKeepsExistingUserFlagsAndPassesUrlAsOneArgument() throws Exception {
        AtomicReference<List<String>> actual = new AtomicReference<>();
        FakeProcess child = new FakeProcess("Starting: Intent", 0);
        RedirectDispatcher.Launch launch = new AmProcessLauncher(command -> {
            actual.set(command); return child;
        }, System::nanoTime, TimeUnit.SECONDS.toNanos(2), 100).prepare("browser.a", URL);
        launch.start(); assertEquals(SUCCEEDED, launch.await());
        assertEquals(List.of("/system/bin/am", "start", "--user", "current", "-a",
                "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE",
                "-f", "0x10000000", "-d", URL, "-p", "browser.a"), actual.get());
        child.assertClosed();
    }

    @Test public void successClosesAllStreamsWithoutDestroyingExitedChild() throws Exception {
        FakeProcess process = new FakeProcess("Starting: Intent { dat=" + URL + " }\n", 0);
        assertEquals(SUCCEEDED, run(process)); process.assertClosed(); assertEquals(0, process.destroyCalls);
    }

    @Test public void nonzeroExitIsFailureEvenWithStartingLine() throws Exception {
        FakeProcess process = new FakeProcess("Starting: Intent\n", 1);
        assertEquals(FAILED, run(process)); process.assertClosed();
    }

    @Test public void exitZeroWithErrorIsFailureEvenAfterLargeOutputAndSplitReads() throws Exception {
        String output = "Starting: Intent { dat=" + URL + " }\n" + "x".repeat(200_000)
                + "\n  Error: Activity not started, unable to resolve " + URL;
        FakeProcess process = new FakeProcess(output, 0); process.stdout.chunk = 3;
        assertEquals(FAILED, run(process)); process.assertClosed();
    }

    @Test public void errorTypeAndExceptionHeadersFailWithoutNewline() throws Exception {
        for (String error : List.of("Error type 3", "Exception occurred while executing 'start':",
                "java.lang.SecurityException: " + URL, "SecurityException: " + URL)) {
            assertEquals(error, FAILED, run(new FakeProcess(error, 0)));
        }
    }

    @Test public void deliveredAndTaskToFrontWarningsAreAccepted() throws Exception {
        for (String output : List.of(
                "Warning: Activity not started, intent has been delivered to currently running top-most instance.",
                "Warning: Activity not started, its current task has been brought to the front")) {
            assertEquals(SUCCEEDED, run(new FakeProcess(output, 0)));
        }
    }

    @Test public void keptCurrentActivityOrCallerWarningIsNotFalseSuccess() throws Exception {
        for (String output : List.of(
                "Warning: Activity not started because the current activity is being kept for the user.",
                "Warning: Activity not started because intent should be handled by the caller")) {
            assertEquals(FAILED, run(new FakeProcess(output, 0)));
        }
    }

    @Test public void errorWordInsideUrlDoesNotLookLikeCommandError() throws Exception {
        assertEquals(SUCCEEDED, run(new FakeProcess(
                "Starting: Intent { dat=https://example.test/Error?Exception=secret }\n", 0)));
    }

    @Test public void interruptionDestroysForcesClosesAndPreservesInterruptFlag() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        CountDownLatch entered = new CountDownLatch(1);
        process.wait = (timeout, unit) -> { entered.countDown(); new CountDownLatch(1).await(); return false; };
        RedirectDispatcher.Launch launch = launch(process); launch.start();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try { launch.await(); fail("expected interruption"); }
            catch (InterruptedException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); thread.interrupt(); thread.join(2000);
        assertFalse(thread.isAlive()); assertTrue(interrupted.get());
        assertEquals(1, process.destroyCalls); assertEquals(1, process.forceCalls); process.assertClosed();
    }

    @Test public void cancellationDestroysActiveProcessAndClosesAllStreams() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        process.wait = (timeout, unit) -> { entered.countDown(); release.await(); return false; };
        RedirectDispatcher.Launch launch = launch(process); launch.start();
        AtomicReference<RedirectDispatcher.Result> result = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { result.set(launch.await()); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        thread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); launch.cancel(); release.countDown();
        thread.join(2000); assertFalse(thread.isAlive()); assertEquals(CANCELLED, result.get());
        assertEquals(1, process.forceCalls); process.assertClosed();
    }

    @Test public void timeoutUsesInjectedMonotonicTimeAndBoundedCleanupWaits() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        AtomicLong clock = new AtomicLong();
        process.wait = (timeout, unit) -> { clock.addAndGet(11); return false; };
        RedirectDispatcher.Launch launch = new AmProcessLauncher(command -> process, clock::get,
                10, 7).prepare("browser.a", URL);
        launch.start(); assertEquals(TIMED_OUT, launch.await());
        assertEquals(1, process.destroyCalls); assertEquals(1, process.forceCalls);
        assertEquals(7, process.longestCleanupMillis); process.assertClosed();
    }

    @Test public void waitExceptionCleansProcessAndNeverReturnsMessageWithUrl() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        process.wait = (timeout, unit) -> { throw new IllegalStateException(URL); };
        assertEquals(FAILED, run(process)); process.assertClosed(); assertEquals(1, process.forceCalls);
    }

    @Test public void streamSetupExceptionStillFindsAndClosesAllStreams() throws Exception {
        FakeProcess process = new FakeProcess("", 0); process.failInputOnce = true;
        assertEquals(FAILED, run(process)); process.assertClosed(); assertEquals(1, process.forceCalls);
    }

    @Test public void destroyExceptionStillForcesAndCloseExceptionDoesNotSkipOtherStreams() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        process.throwDestroy = true; process.stdin.throwOnClose = true;
        process.wait = (timeout, unit) -> { throw new IllegalStateException("wait failure"); };
        assertEquals(FAILED, run(process)); process.assertClosed(); assertEquals(1, process.forceCalls);
    }

    @Test public void closeBeforeAwaitStillReapsChildAndClosesStreams() throws Exception {
        FakeProcess process = new FakeProcess("", 0);
        RedirectDispatcher.Launch launch = launch(process); launch.start(); launch.cancel(); launch.close();
        assertEquals(1, process.forceCalls); process.assertClosed();
        launch.close(); assertEquals(1, process.forceCalls); // idempotent
    }

    @Test public void cancelBeforeStartDoesNotCreateAProcess() throws Exception {
        AtomicBoolean started = new AtomicBoolean();
        RedirectDispatcher.Launch launch = new AmProcessLauncher(command -> {
            started.set(true); return new FakeProcess("", 0);
        }, System::nanoTime, 1, 1).prepare("browser.a", URL);
        launch.cancel(); launch.start(); assertEquals(CANCELLED, launch.await()); assertFalse(started.get());
    }

    @Test public void outputReadFailureDoesNotClaimSuccess() throws Exception {
        FakeProcess process = new FakeProcess("Starting: Intent", 0); process.stdout.throwOnRead = true;
        assertEquals(FAILED, run(process)); process.assertClosed();
    }

    @Test public void missingOutputEofIsBoundedAndClosesReaderInsteadOfFalseSuccess() throws Exception {
        FakeProcess process = new FakeProcess("", 0); process.stdout.holdUntilClosed = true;
        assertEquals(FAILED, run(process)); process.assertClosed();
        assertTrue(process.stdout.readEnded.await(2, TimeUnit.SECONDS));
    }

    @Test public void productionDispatcherAndLauncherShareFactoryFailureCleanupAndRetryPath() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.BlockingQueue<RedirectDispatcher.Result> results = new java.util.concurrent.LinkedBlockingQueue<>();
        FakeProcess broken = new FakeProcess("", 0), success = new FakeProcess("Starting: Intent", 0);
        broken.throwDestroy = true;
        broken.wait = (timeout, unit) -> { throw new IllegalStateException(URL); };
        RedirectDispatcher dispatcher = new RedirectDispatcher(new AmProcessLauncher(command -> {
            int attempt = attempts.getAndIncrement();
            if (attempt == 0) throw new IOException(URL);
            return attempt == 1 ? broken : success;
        }, System::nanoTime, TimeUnit.SECONDS.toNanos(2), 100),
                (config, url, result) -> results.add(result));
        try {
            dispatcher.configure("browser.a", false);
            for (RedirectDispatcher.Result expected : List.of(FAILED, FAILED, SUCCEEDED)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                RedirectDispatcher.Submission submission;
                do {
                    submission = dispatcher.trySubmit(dispatcher.snapshot(), URL);
                    if (submission != RedirectDispatcher.Submission.REJECTED) break;
                    Thread.yield();
                } while (System.nanoTime() < deadline);
                assertEquals(RedirectDispatcher.Submission.ACCEPTED, submission);
                assertEquals(expected, results.poll(2, TimeUnit.SECONDS));
            }
            broken.assertClosed(); success.assertClosed(); assertEquals(1, broken.forceCalls);
        } finally {
            dispatcher.close(); assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void realJvmChildFloodsPipeThenEmitsErrorWithExitZero() throws Exception {
        AtomicReference<Process> actual = new AtomicReference<>();
        RedirectDispatcher.Launch launch = new AmProcessLauncher(command -> {
            Process child = javaChild("flood"); actual.set(child); return child;
        }, System::nanoTime, TimeUnit.SECONDS.toNanos(5), 100).prepare("browser.a", URL);
        launch.start(); assertEquals(FAILED, launch.await()); assertFalse(actual.get().isAlive());
    }

    @Test public void realJvmChildCancellationReapsActualOsProcess() throws Exception {
        Process actual = javaChild("wait");
        RedirectDispatcher.Launch launch = new AmProcessLauncher(command -> actual, System::nanoTime,
                TimeUnit.SECONDS.toNanos(5), 100).prepare("browser.a", URL);
        try {
            launch.start(); launch.cancel(); assertEquals(CANCELLED, launch.await());
            assertFalse(actual.isAlive());
        } finally { actual.destroyForcibly(); }
    }

    private static Process javaChild(String mode) throws IOException {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new ProcessBuilder(new File(System.getProperty("java.home"), "bin/" + executable).getPath(),
                "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), mode)
                .redirectErrorStream(true).start();
    }

    /** Local OS-process fixture only, never /system/bin/am or an Android activity. */
    public static final class ProcessFixture {
        public static void main(String[] args) throws Exception {
            if ("flood".equals(args[0])) {
                for (int i = 0; i < 2048; i++) System.out.println("x".repeat(1024));
                System.err.println("Error: Activity not started, simulated test error");
            } else { new CountDownLatch(1).await(); }
        }
    }

    private interface Wait { boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException; }
    private static final class FakeProcess extends Process {
        final TrackingInput stdout, stderr = new TrackingInput("");
        final TrackingOutput stdin = new TrackingOutput();
        final int exit;
        volatile boolean alive = true;
        boolean failInputOnce, throwDestroy;
        int destroyCalls, forceCalls;
        long longestCleanupMillis;
        Wait wait;
        FakeProcess(String output, int exit) { stdout = new TrackingInput(output); this.exit = exit; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() {
            if (failInputOnce) { failInputOnce = false; throw new IllegalStateException(URL); }
            return stdout;
        }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { throw new AssertionError("unbounded waitFor is forbidden"); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (destroyCalls > 0) {
                longestCleanupMillis = Math.max(longestCleanupMillis, unit.toMillis(timeout));
                return !alive;
            }
            if (wait != null) return wait.waitFor(timeout, unit);
            alive = false; return true;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException(); return exit;
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() {
            destroyCalls++; if (throwDestroy) throw new IllegalStateException("destroy failed");
        }
        @Override public Process destroyForcibly() { forceCalls++; alive = false; return this; }
        void assertClosed() { assertTrue(stdout.closed); assertTrue(stderr.closed); assertTrue(stdin.closed); }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed, throwOnRead, holdUntilClosed;
        final CountDownLatch closeSignal = new CountDownLatch(1), readEnded = new CountDownLatch(1);
        int chunk = Integer.MAX_VALUE;
        TrackingInput(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public synchronized int read(byte[] data, int offset, int length) {
            if (throwOnRead) throw new IllegalStateException("simulated output read failure");
            if (holdUntilClosed) {
                try { closeSignal.await(); return -1; }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                finally { readEnded.countDown(); }
            }
            return super.read(data, offset, Math.min(length, chunk));
        }
        @Override public void close() { closed = true; closeSignal.countDown(); }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed, throwOnClose;
        @Override public void close() throws IOException {
            closed = true; if (throwOnClose) throw new IOException("close failure");
        }
    }
}
