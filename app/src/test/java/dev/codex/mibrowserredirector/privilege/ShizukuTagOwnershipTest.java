package dev.codex.mibrowserredirector.privilege;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class ShizukuTagOwnershipTest {
    private static final AtomicInteger TAG_SEQUENCE = new AtomicInteger();

    @Test
    public void oldActivityDetachMustNotClearNewActivityCallbackForSameTag() {
        String stableTag = nextTag();
        ShizukuTagOwnership activityA = ShizukuTagOwnership.forTag(stableTag);
        ShizukuTagOwnership activityB = ShizukuTagOwnership.forTag(stableTag);
        ShizukuTagOwnership.Lease a = activityA.newLease();
        ShizukuTagOwnership.Lease b = activityB.newLease();
        Set<String> sdkCallbacks = new HashSet<>();
        activityA.bind(a, () -> sdkCallbacks.add("A"));
        activityB.bind(b, () -> sdkCallbacks.add("B"));

        // SDK 13.1.5 unbind(args, connection, false) clears ALL callbacks for this tag.
        // This is the real adapter seam, not a per-session fake unbind.
        activityA.detach(a, sdkCallbacks::clear);

        assertTrue("old A.detach erased new B callback via tag-wide SDK unbind", sdkCallbacks.contains("B"));
        assertEquals(2, sdkCallbacks.size());
    }

    @Test
    public void registryIsProcessScopedAcrossAdapterInstances() {
        String tag = nextTag();
        assertSame(ShizukuTagOwnership.forTag(tag), ShizukuTagOwnership.forTag(tag));
    }

    @Test
    public void allocatingButNotBindingNewSessionDoesNotTakeOwnership() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        owner.bind(a, () -> { });
        ShizukuTagOwnership.Lease notBound = owner.newLease();
        assertTrue(owner.isCurrent(a));
        assertFalse(owner.isCurrent(notBound));
        assertFalse(owner.detach(notBound, () -> { throw new AssertionError("unbound lease has no SDK entry"); }));
        assertThrows(IllegalStateException.class, () -> owner.remove(notBound, () -> { }));
        assertTrue(owner.isCurrent(a));
    }

    @Test
    public void ownerIsRegisteredBeforeActualBindAndOldRemoveIsRejected() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(a, () -> { });
        owner.bind(b, () -> {
            assertTrue(owner.isCurrent(b));
            assertFalse(owner.isCurrent(a));
        });
        AtomicInteger removals = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> owner.remove(a, removals::incrementAndGet));
        assertEquals(0, removals.get());
        assertTrue(owner.isCurrent(b));
    }

    @Test
    public void onlyLatestLeaseCanPerformTagWideDetachAndItIsIdempotent() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        Set<String> callbacks = new HashSet<>();
        owner.bind(a, () -> callbacks.add("A"));
        owner.bind(b, () -> callbacks.add("B"));
        assertTrue(owner.detach(b, callbacks::clear));
        assertTrue(callbacks.isEmpty());
        assertFalse(owner.isCurrent(b));
        assertFalse(owner.detach(a, () -> { throw new AssertionError("A must stay retired"); }));
        assertFalse(owner.detach(b, () -> { throw new AssertionError("duplicate detach"); }));
    }

    @Test
    public void detachmentDoesNotPreventExplicitRemovalOfSameDaemonLease() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        AtomicInteger removals = new AtomicInteger();
        owner.bind(a, () -> { });
        owner.detach(a, () -> { });
        owner.remove(a, removals::incrementAndGet);
        assertEquals(1, removals.get());
        assertFalse(owner.isCurrent(a));
        assertThrows(IllegalStateException.class, () -> owner.remove(a, removals::incrementAndGet));
        assertEquals(1, removals.get());
    }

    @Test
    public void removingLatestLeaseNeverRevivesEarlierLease() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(a, () -> { });
        owner.bind(b, () -> { });
        owner.remove(b, () -> { });
        assertFalse(owner.detach(a, () -> { throw new AssertionError("old tag-wide detach"); }));
        assertThrows(IllegalStateException.class, () -> owner.remove(a, () -> { }));
        assertFalse(owner.isCurrent(a));
        ShizukuTagOwnership.Lease c = owner.newLease();
        owner.bind(c, () -> { });
        assertTrue(owner.isCurrent(c));
        assertFalse(owner.detach(b, () -> { throw new AssertionError("removed B must not affect C"); }));
    }

    @Test
    public void partialBindFailureDoesNotRollBackOwnerToOlderActivity() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        Set<String> callbacks = new HashSet<>();
        owner.bind(a, () -> callbacks.add("A"));
        assertThrows(IllegalStateException.class, () -> owner.bind(b, () -> {
            callbacks.add("B");
            throw new IllegalStateException("manager failed after registering B");
        }));
        assertFalse(owner.detach(a, callbacks::clear));
        assertTrue(callbacks.contains("B"));
        assertTrue(owner.detach(b, callbacks::clear));
        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void sameLeaseCannotBeReboundToResurrectAStaleOwner() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(a, () -> { });
        owner.bind(b, () -> { });
        assertThrows(IllegalStateException.class, () -> owner.bind(a, () -> { }));
        assertTrue(owner.isCurrent(b));
        assertFalse(owner.detach(a, () -> { throw new AssertionError("A revived"); }));
    }

    @Test
    public void removeFailureReleasesGateButDoesNotPermitStaleRetryAfterNewBind() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        owner.bind(a, () -> { });
        assertThrows(IllegalStateException.class, () -> owner.remove(a, () -> {
            throw new IllegalStateException("manager changed");
        }));
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(b, () -> { });
        assertThrows(IllegalStateException.class, () -> owner.remove(a, () -> {
            throw new AssertionError("retry would remove B");
        }));
    }

    @Test
    public void detachFailureReleasesGateAndKeepsNewOwnerSafe() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        owner.bind(a, () -> { });
        assertThrows(IllegalStateException.class, () -> owner.detach(a, () -> {
            throw new IllegalStateException("unbind failed");
        }));
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(b, () -> { });
        assertFalse(owner.detach(a, () -> { throw new AssertionError("late unbind would clear B"); }));
    }

    @Test
    public void busyBindRejectsIndependentStopRemoveWithoutWaiting() throws Exception {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        BlockingCall binding = new BlockingCall();
        FutureTask<Void> worker = start(() -> owner.bind(a, binding));
        try {
            assertTrue(binding.entered.await(1, TimeUnit.SECONDS));
            assertTrue(owner.isCurrent(a)); // must be nonblocking, including on main
            assertRejectedPromptly(() -> owner.remove(a, () -> { throw new AssertionError("concurrent remove"); }));
            assertRejectedPromptly(() -> owner.bind(b, () -> { throw new AssertionError("concurrent bind"); }));
            assertFalse(owner.detach(a, () -> { throw new AssertionError("unbind during bind"); }));
            assertTrue(owner.isCurrent(a));
        } finally {
            binding.release.countDown();
            worker.get(2, TimeUnit.SECONDS);
        }
        // Rejected-before-admission B can now bind; it was never registered while A was in flight.
        owner.bind(b, () -> { });
        assertTrue(owner.isCurrent(b));
    }

    @Test
    public void busyDetachHoldsOrderingThroughActualUnbindCompletion() throws Exception {
        assertTagMutationBlocksOtherCalls(false);
    }

    @Test
    public void busyRemoveHoldsOrderingThroughActualManagerReply() throws Exception {
        assertTagMutationBlocksOtherCalls(true);
    }

    private static void assertTagMutationBlocksOtherCalls(boolean remove) throws Exception {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(a, () -> { });
        BlockingCall mutation = new BlockingCall();
        List<String> order = new ArrayList<>();
        FutureTask<Void> worker = start(() -> {
            Runnable call = () -> { mutation.run(); order.add("A mutation returned"); };
            if (remove) owner.remove(a, call);
            else owner.detach(a, call);
        });
        try {
            assertTrue(mutation.entered.await(1, TimeUnit.SECONDS));
            assertRejectedPromptly(() -> owner.bind(b, () -> order.add("B bind")));
            assertRejectedPromptly(() -> owner.remove(a, () -> { throw new AssertionError("duplicate mutation"); }));
            assertFalse(owner.detach(a, () -> { throw new AssertionError("parallel detach"); }));
        } finally {
            mutation.release.countDown();
            worker.get(2, TimeUnit.SECONDS);
        }
        owner.bind(b, () -> order.add("B bind"));
        assertEquals(Arrays.asList("A mutation returned", "B bind"), order);
        assertFalse(owner.detach(a, () -> { throw new AssertionError("late A detach"); }));
    }

    @Test
    public void differentTagsDoNotWaitForBlockedManagerCall() throws Exception {
        ShizukuTagOwnership ownerA = freshOwner();
        ShizukuTagOwnership ownerB = freshOwner();
        BlockingCall blocked = new BlockingCall();
        FutureTask<Void> worker = start(() -> ownerA.bind(ownerA.newLease(), blocked));
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            FutureTask<Void> other = start(() -> {
                ShizukuTagOwnership.Lease lease = ownerB.newLease();
                ownerB.bind(lease, () -> { });
                ownerB.detach(lease, () -> { });
                ownerB.remove(lease, () -> { });
            });
            other.get(1, TimeUnit.SECONDS);
        } finally {
            blocked.release.countDown();
            worker.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void callbacksCannotReenterSameTagMutationOnSameThread() {
        ShizukuTagOwnership owner = freshOwner();
        ShizukuTagOwnership.Lease a = owner.newLease();
        ShizukuTagOwnership.Lease b = owner.newLease();
        owner.bind(a, () -> {
            assertThrows(IllegalStateException.class, () -> owner.remove(a, () -> { }));
            assertThrows(IllegalStateException.class, () -> owner.bind(b, () -> { }));
            assertFalse(owner.detach(a, () -> { throw new AssertionError("reentrant detach"); }));
        });
        assertTrue(owner.isCurrent(a));
    }

    @Test
    public void wrongTagLeaseCannotMutateAnotherTag() {
        ShizukuTagOwnership ownerA = freshOwner();
        ShizukuTagOwnership ownerB = freshOwner();
        ShizukuTagOwnership.Lease lease = ownerA.newLease();
        assertThrows(IllegalArgumentException.class, () -> ownerB.bind(lease, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> ownerB.detach(lease, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> ownerB.remove(lease, () -> { }));
    }

    private static void assertRejectedPromptly(Runnable call) throws Exception {
        FutureTask<Void> attempt = start(() -> assertThrows(IllegalStateException.class, call::run));
        attempt.get(1, TimeUnit.SECONDS);
    }

    private static FutureTask<Void> start(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Thread thread = new Thread(task, "tag-ownership-test-worker");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static final class BlockingCall implements Runnable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public void run() {
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    release.await();
                    finished = true;
                } catch (InterruptedException ignored) {
                    // Binder may not cooperate. Gate must remain occupied until real return.
                }
            }
        }
    }

    private static String nextTag() { return "ownership-test-" + TAG_SEQUENCE.incrementAndGet(); }

    private static ShizukuTagOwnership freshOwner() { return ShizukuTagOwnership.forTag(nextTag()); }
}
