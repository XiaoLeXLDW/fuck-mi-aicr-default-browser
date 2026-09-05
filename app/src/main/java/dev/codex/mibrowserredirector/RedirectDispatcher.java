package dev.codex.mibrowserredirector;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Single-worker redirect queue. Callbacks use snapshot(), trySubmit(), tryRecord().
 * trySubmit never waits for a lock or calls a launcher. Contention fails open.
 * Configuration and start share a separate gate, never the Binder service monitor.
 */
final class RedirectDispatcher implements AutoCloseable {
    enum Submission { ACCEPTED, DUPLICATE, REJECTED }
    enum Result { SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    private enum State { IN_FLIGHT, SUCCEEDED, FAILED }

    interface Launcher {
        // Preparation has no external launch effect and runs OUTSIDE the gate.
        Launch prepare(String target, String url) throws Exception;
    }

    interface Launch extends AutoCloseable {
        // Commit only: create/track the child, NEVER wait for am/system callbacks here.
        void start() throws Exception;
        Result await() throws InterruptedException;
        // Only set a cancellation flag; actual bounded cleanup belongs to close().
        void cancel();
        @Override void close();
    }

    interface Listener {
        // All notifications run under the gate: local bookkeeping only, no Binder/wait.
        default void configured(Snapshot config) { }
        default void accepted(Snapshot config, String url) { }
        void finished(Snapshot config, String url, Result result);
    }

    static final class Snapshot {
        final long generation;
        final String target;
        final boolean enabled;
        final boolean observeOnly;
        Snapshot(long generation, String target, boolean enabled, boolean observeOnly) {
            this.generation = generation;
            this.target = target;
            this.enabled = enabled;
            this.observeOnly = observeOnly;
        }
    }

    private static final class Key {
        final long generation;
        final String target, url;
        Key(Snapshot config, String url) {
            generation = config.generation; target = config.target; this.url = url;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return generation == key.generation && target.equals(key.target) && url.equals(key.url);
        }
        @Override public int hashCode() {
            return 31 * (31 * Long.hashCode(generation) + target.hashCode()) + url.hashCode();
        }
    }

    private static final class Entry {
        final Snapshot config;
        final Key key;
        State state = State.IN_FLIGHT;
        long succeededAt;
        Launch launch;
        Entry(Snapshot config, String url) { this.config = config; key = new Key(config, url); }
    }

    private final ReentrantLock gate = new ReentrantLock();
    private final Condition workAvailable = gate.newCondition();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final LinkedHashMap<Key, Entry> dedupe = new LinkedHashMap<>();
    private final Launcher launcher;
    private final Listener listener;
    private final LongSupplier nanoClock;
    private final int capacity;
    private final long duplicateNanos;
    private final Thread worker;
    private volatile Snapshot config = new Snapshot(0, null, false, false);
    private boolean closed;
    private Entry active;

    RedirectDispatcher(Launcher launcher, Listener listener) {
        this(launcher, listener, System::nanoTime, 8, TimeUnit.MILLISECONDS.toNanos(1500));
    }

    RedirectDispatcher(Launcher launcher, Listener listener, LongSupplier nanoClock,
            int capacity, long duplicateNanos) {
        if (capacity < 1 || duplicateNanos < 0) throw new IllegalArgumentException();
        this.launcher = launcher;
        this.listener = listener;
        this.nanoClock = nanoClock;
        this.capacity = capacity;
        this.duplicateNanos = duplicateNanos;
        worker = new Thread(this::work, "mi-browser-redirect");
        worker.setDaemon(true);
        worker.start(); // Never lazily create a worker from activityStarting.
    }

    Snapshot snapshot() { return config; }

    void configure(String target, boolean observeOnly) {
        if (target == null) throw new IllegalArgumentException("target is required");
        replace(target, true, observeOnly);
    }

    void disable() { replace(null, false, false); }

    private void replace(String target, boolean enabled, boolean observeOnly) {
        gate.lock();
        try {
            config = new Snapshot(config.generation + 1, target, enabled && !closed, observeOnly);
            queue.clear();
            dedupe.clear();
            if (active != null && active.launch != null) active.launch.cancel();
            try { listener.configured(config); } catch (RuntimeException ignored) { }
            // A start already committed under this gate cannot be undone. No old
            // task can cross start() AFTER this configuration operation returns.
        } finally { gate.unlock(); }
    }

    /** Local diagnostic publication only; never wait, and never publish for stale config. */
    void tryRecord(Snapshot expected, Runnable record) {
        if (!gate.tryLock()) return;
        try {
            if (config == expected && !closed) record.run();
        } finally { gate.unlock(); }
    }

    Submission trySubmit(Snapshot expected, String url) {
        if (expected == null || url == null || !gate.tryLock()) return Submission.REJECTED;
        try {
            if (closed || config != expected || !config.enabled || config.observeOnly) {
                return Submission.REJECTED;
            }
            long now = nanoClock.getAsLong();
            Iterator<Map.Entry<Key, Entry>> iterator = dedupe.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next().getValue();
                if (entry.state == State.SUCCEEDED && now - entry.succeededAt >= duplicateNanos) {
                    iterator.remove();
                }
            }
            Key key = new Key(expected, url);
            Entry previous = dedupe.get(key);
            if (previous != null) return Submission.DUPLICATE;
            // Do not reserve a dedupe key for rejected work.
            if (queue.size() >= capacity) return Submission.REJECTED;
            // Bounded successful history; never evict queued/running reservations.
            if (dedupe.size() >= capacity + 65) {
                iterator = dedupe.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().getValue().state == State.SUCCEEDED) {
                        iterator.remove(); break;
                    }
                }
            }
            Entry entry = new Entry(expected, url);
            dedupe.put(entry.key, entry);
            try {
                queue.addLast(entry);
                // Publish BEFORE releasing the gate: an instant launch result must
                // never be overwritten by a late "queued" message from the caller.
                try { listener.accepted(expected, url); } catch (RuntimeException ignored) { }
                workAvailable.signal();
            } catch (RuntimeException e) {
                queue.remove(entry);
                dedupe.remove(entry.key);
                return Submission.REJECTED;
            }
            return Submission.ACCEPTED;
        } finally { gate.unlock(); }
    }

    private void work() {
        while (true) {
            Entry entry;
            gate.lock();
            try {
                while (queue.isEmpty() && !closed) {
                    try { workAvailable.await(); }
                    catch (InterruptedException ignored) { /* recheck closed */ }
                }
                if (closed) return;
                entry = queue.removeFirst();
                active = entry;
            } finally { gate.unlock(); }
            run(entry);
            // Interruption cancels that launch, not every subsequent launch.
            Thread.interrupted();
        }
    }

    private void run(Entry entry) {
        Result result = Result.CANCELLED;
        Launch launch = null;
        try {
            launch = launcher.prepare(entry.key.target, entry.key.url);
            gate.lock();
            try {
                if (closed || config != entry.config || !config.enabled || config.observeOnly) return;
                entry.launch = launch;
                launch.start(); // Atomic pre-launch check + external commit vs disable/configure.
            } finally { gate.unlock(); }
            result = launch.await(); // No gate/service lock while am waits on system callbacks.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Result.CANCELLED;
        } catch (Exception e) {
            // Never expose exception messages: ProcessBuilder may include the full URL.
            result = Result.FAILED;
        } finally {
            if (launch != null) {
                try { launch.close(); }
                catch (RuntimeException ignored) { result = Result.FAILED; }
            }
            gate.lock();
            try {
                if (active == entry) active = null;
                if (config == entry.config && !closed && dedupe.get(entry.key) == entry) {
                    if (result == Result.SUCCEEDED) {
                        entry.state = State.SUCCEEDED;
                        entry.succeededAt = nanoClock.getAsLong();
                    } else {
                        entry.state = State.FAILED;
                        dedupe.remove(entry.key);
                    }
                    // Listener is local bookkeeping only, must not call Binder or wait.
                    try { listener.finished(entry.config, entry.key.url, result); }
                    catch (RuntimeException ignored) { /* keep the dispatcher alive */ }
                }
            } finally { gate.unlock(); }
        }
    }

    @Override public void close() {
        gate.lock();
        try {
            closed = true;
            config = new Snapshot(config.generation + 1, null, false, false);
            queue.clear();
            dedupe.clear();
            if (active != null && active.launch != null) active.launch.cancel();
            workAvailable.signal();
        } finally { gate.unlock(); }
        worker.interrupt();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        worker.join(Math.max(1, unit.toMillis(timeout)));
        return !worker.isAlive();
    }
}
