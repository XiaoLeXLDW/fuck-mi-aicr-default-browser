package dev.codex.mibrowserredirector;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Owns one child from start through finally-cleanup. No Android dependencies. */
final class AmProcessLauncher implements RedirectDispatcher.Launcher {
    interface ProcessFactory {
        // Must merge stderr into stdout. Must return after creation, not command completion.
        Process start(List<String> command) throws IOException;
    }

    private final ProcessFactory factory;
    private final LongSupplier nanoClock;
    private final long timeoutNanos;
    private final long cleanupMillis;

    AmProcessLauncher() {
        this(command -> new ProcessBuilder(command).redirectErrorStream(true).start(),
                System::nanoTime, TimeUnit.SECONDS.toNanos(10), 100);
    }

    AmProcessLauncher(ProcessFactory factory, LongSupplier nanoClock,
            long timeoutNanos, long cleanupMillis) {
        if (timeoutNanos <= 0 || cleanupMillis <= 0) throw new IllegalArgumentException();
        this.factory = factory;
        this.nanoClock = nanoClock;
        this.timeoutNanos = timeoutNanos;
        this.cleanupMillis = cleanupMillis;
    }

    @Override public RedirectDispatcher.Launch prepare(String target, String url) {
        // Preserve existing global-controller, current-user and no-fallback behavior.
        return new Child(Arrays.asList("/system/bin/am", "start", "--user", "current",
                "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE",
                "-f", "0x10000000", "-d", url, "-p", target));
    }

    private final class Child implements RedirectDispatcher.Launch {
        private final List<String> command;
        private volatile boolean cancelled;
        private Process process;
        private InputStream stdout, stderr;
        private OutputStream stdin;
        private OutputDrain drain;
        private Thread reader;
        private boolean closed;
        private long startedAt;

        Child(List<String> command) { this.command = command; }

        @Override public void start() throws IOException {
            if (cancelled || closed) return;
            startedAt = nanoClock.getAsLong();
            process = factory.start(command); // Track immediately, even if stream setup throws.
            // Everything after process creation (stream access/thread creation) belongs
            // to await(), outside the dispatcher's pre-launch gate.
        }

        @Override public RedirectDispatcher.Result await() throws InterruptedException {
            try {
                if (cancelled || process == null) return RedirectDispatcher.Result.CANCELLED;
                stdout = process.getInputStream();
                stderr = process.getErrorStream();
                stdin = process.getOutputStream();
                closeQuietly(stdin); // am needs no input.
                drain = new OutputDrain(stdout);
                reader = new Thread(drain, "mi-browser-am-output");
                reader.setDaemon(true);
                reader.start();
                while (true) {
                    if (cancelled) return RedirectDispatcher.Result.CANCELLED;
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    long remaining = timeoutNanos - (nanoClock.getAsLong() - startedAt);
                    if (remaining <= 0) return RedirectDispatcher.Result.TIMED_OUT;
                    if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                            TimeUnit.NANOSECONDS)) break;
                }
                long remaining = timeoutNanos - (nanoClock.getAsLong() - startedAt);
                // An exit without complete output is NOT enough to claim success.
                if (remaining <= 0 || !drain.done.await(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(cleanupMillis)),
                        TimeUnit.NANOSECONDS)) return RedirectDispatcher.Result.FAILED;
                if (cancelled) return RedirectDispatcher.Result.CANCELLED;
                return process.exitValue() == 0 && !drain.failed && !drain.output.rejected
                        ? RedirectDispatcher.Result.SUCCEEDED : RedirectDispatcher.Result.FAILED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (RuntimeException e) {
                // Exceptions/output can contain the complete URL; report only an enum.
                return RedirectDispatcher.Result.FAILED;
            } finally { close(); }
        }

        @Override public void cancel() { cancelled = true; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            // Clear interruption temporarily so cleanup gets bounded wait opportunities.
            boolean interrupted = Thread.interrupted();
            try {
                if (process != null) {
                    // Attempt both termination stages even if one operation throws.
                    if (aliveOrUnknown()) {
                        try { process.destroy(); } catch (RuntimeException ignored) { }
                        interrupted |= waitForCleanup();
                        if (aliveOrUnknown()) {
                            try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
                            interrupted |= waitForCleanup();
                        }
                    }
                }
            } finally {
                // Retrieve any streams not reached if setup itself threw.
                if (process != null) {
                    if (stdin == null) try { stdin = process.getOutputStream(); } catch (RuntimeException ignored) { }
                    if (stdout == null) try { stdout = process.getInputStream(); } catch (RuntimeException ignored) { }
                    if (stderr == null) try { stderr = process.getErrorStream(); } catch (RuntimeException ignored) { }
                }
                closeQuietly(stdin);
                closeQuietly(stdout);
                closeQuietly(stderr);
                if (reader != null) {
                    reader.interrupt();
                    try { reader.join(cleanupMillis); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private boolean aliveOrUnknown() {
            try { return process.isAlive(); }
            catch (RuntimeException ignored) { return true; }
        }

        private boolean waitForCleanup() {
            try { process.waitFor(cleanupMillis, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { return true; }
            catch (RuntimeException ignored) { }
            return false;
        }
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException | RuntimeException ignored) { }
    }

    private static final class OutputDrain implements Runnable {
        final CountDownLatch done = new CountDownLatch(1);
        final CommandOutput output = new CommandOutput();
        final InputStream input;
        volatile boolean failed;
        OutputDrain(InputStream input) { this.input = input; }
        @Override public void run() {
            byte[] buffer = new byte[1024];
            try {
                int length;
                while ((length = input.read(buffer)) != -1) {
                    for (int i = 0; i < length; i++) output.accept(buffer[i] & 0xff);
                }
                output.endLine();
            } catch (IOException | RuntimeException e) { failed = true; }
            finally { done.countDown(); }
        }
    }

    /** Retains only a bounded line prefix, never returns or logs child output.
     * Android versions differ in am exit codes. "Starting: Intent" is printed
     * BEFORE the attempt, not an acknowledgement. Reject errors even with exit=0.
     * Delivered-to-top / task-to-front warnings are successful dispatches, while
     * switches-cancelled / return-to-caller warnings must remain retryable.
     */
    private static final class CommandOutput {
        private final StringBuilder prefix = new StringBuilder(192);
        boolean rejected;
        void accept(int value) {
            if (value == '\n' || value == '\r') { endLine(); return; }
            if (prefix.length() == 0 && (value == ' ' || value == '\t')) return;
            if (prefix.length() < 192) prefix.append((char) value);
        }
        void endLine() {
            String line = prefix.toString().toLowerCase(Locale.ROOT);
            if (line.startsWith("error") || line.startsWith("exception")
                    || line.startsWith("java.") || line.startsWith("securityexception")
                    || line.startsWith("status: timeout")
                    || (line.startsWith("warning: activity not started")
                        && !line.contains("delivered to currently running")
                        && !line.contains("task has been brought to the front"))) rejected = true;
            prefix.setLength(0);
        }
    }
}
