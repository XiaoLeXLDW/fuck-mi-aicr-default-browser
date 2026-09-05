package dev.codex.mibrowserredirector.privilege;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shizuku 13.1.5 caches connections process-wide by tag (or class name without a tag).
 * Its unbind(false) clears that ENTIRE tag, not only the supplied ServiceConnection.
 *
 * A lease is registered together with actual bind under a non-waiting gate. All manager
 * checks and actual bind/detach/remove calls must stay INSIDE the supplied action; checking
 * ownership then calling outside the gate would permit a stale unbind to affect a new lease.
 * The gate also orders the IO bind/detach lane against the independent stop/remove lane.
 * It never holds a monitor or waits for RPC completion: contention fails fast (detach skips).
 * No method here may dispatch its action asynchronously or wait on the main thread.
 *
 * Failed/removed/detached latest leases remain tombstones: an older lease is never revived.
 * A bind exception may have registered an SDK callback already, so failure cannot roll back
 * ownership to an older session. Stale detach only invalidates its local callback in the adapter;
 * the old SDK connection entry can remain until the next safe tag-wide cleanup or Binder death.
 * Registry entries retain only tag/lease state, never an Activity, callback, or Binder.
 */
final class ShizukuTagOwnership {
    private static final ConcurrentHashMap<String, ShizukuTagOwnership> TAGS = new ConcurrentHashMap<>();

    static ShizukuTagOwnership forTag(String tag) {
        return TAGS.computeIfAbsent(tag, ignored -> new ShizukuTagOwnership());
    }

    static final class Lease {
        private final ShizukuTagOwnership tag;
        private boolean registered;
        private volatile boolean detached;
        private volatile boolean removed;

        private Lease(ShizukuTagOwnership tag) { this.tag = tag; }
    }

    private volatile Lease current;
    private final AtomicBoolean busy = new AtomicBoolean();

    private ShizukuTagOwnership() { }

    Lease newLease() { return new Lease(this); }

    void bind(Lease lease, Runnable call) {
        requireLease(lease);
        enter();
        try {
            if (lease.registered) throw new IllegalStateException("Shizuku binding lease already used");
            lease.registered = true;
            current = lease;
            call.run();
        } finally {
            busy.set(false);
        }
    }

    boolean detach(Lease lease, Runnable call) {
        requireLease(lease);
        if (!busy.compareAndSet(false, true)) return false;
        try {
            if (current != lease || lease.detached || lease.removed) return false;
            call.run();
            lease.detached = true;
            return true;
        } finally {
            busy.set(false);
        }
    }

    void remove(Lease lease, Runnable call) {
        requireLease(lease);
        enter();
        try {
            if (current != lease || lease.removed) {
                throw new IllegalStateException("Shizuku tag 已由更新会话接管或已移除，拒绝旧会话移除服务");
            }
            call.run();
            lease.removed = true;
        } finally {
            busy.set(false);
        }
    }

    boolean isCurrent(Lease lease) {
        return lease != null && current == lease && !lease.detached && !lease.removed;
    }

    private void requireLease(Lease lease) {
        if (lease == null || lease.tag != this) throw new IllegalArgumentException("Wrong Shizuku tag lease");
    }

    private void enter() {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("Shizuku tag 调用仍在进行，未发起并行绑定或移除");
        }
    }
}
