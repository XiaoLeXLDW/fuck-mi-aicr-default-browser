package dev.codex.mibrowserredirector;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import static org.junit.Assert.*;

/** Historical, deliberately RED simulation of v0.3.1's queue/dedupe/cleanup patterns.
 * Not the Android service or a phone test. Kept outside the regular test source set.
 */
public class LegacyRedirectPatternsTest {
    static final class Legacy {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean enabled = true;
        String target = "browser.a", lastUrl;
        int starts;
        String startedTarget;
        void submit(String url) {
            if (!enabled || url.equals(lastUrl)) return; // same URL within 1500ms
            String selected = target;
            queue.add(() -> { starts++; startedTarget = selected; });
            lastUrl = url; // stored on enqueue, including launches that later fail
        }
        void disable() { enabled = false; }
    }
    @Test public void disableMustDiscardQueuedLaunch() {
        Legacy old = new Legacy(); old.submit("https://example.test/"); old.disable();
        old.queue.remove().run(); assertEquals(0, old.starts);
    }
    @Test public void configChangeMustDiscardOldTarget() {
        Legacy old = new Legacy(); old.submit("https://example.test/"); old.target = "browser.b";
        old.queue.remove().run(); assertNull(old.startedTarget);
    }
    @Test public void failureMustAllowImmediateRetry() {
        Legacy old = new Legacy(); old.submit("https://example.test/");
        old.queue.remove().run(); // simulated exit=1: old path leaves lastUrl untouched
        old.submit("https://example.test/"); assertEquals(1, old.queue.size());
    }
    @Test public void interruptedWaitMustDestroyChild() {
        LegacyProcess process = new LegacyProcess(); process.interrupted = true;
        try { legacyLaunch(process); }
        finally { Thread.interrupted(); }
        assertTrue("old interrupted catch never invokes process.destroy", process.destroyed);
    }
    @Test public void exitZeroWithAmErrorMustNotSucceed() {
        LegacyProcess process = new LegacyProcess();
        assertFalse("exit=0 with unread Error output", legacyLaunch(process));
    }

    private static boolean legacyLaunch(Process process) {
        // v0.3.1 wait/catch structure, with only ProcessBuilder.start replaced by DI.
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroy(); return false; }
            return process.exitValue() == 0;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); return false;
        }
    }
    private static final class LegacyProcess extends Process {
        boolean interrupted, destroyed;
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interrupted) throw new InterruptedException(); return true;
        }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream("Error: Activity not started".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    }
}
