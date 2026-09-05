package dev.codex.mibrowserredirector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A stop has one monotonic budget, including every Endpoint/Removal call. The caller supervises
 * a separate, single daemon worker; call stop off the UI thread, on a coordinator that is not
 * queued behind other Binder work. No RPC is performed by the supervisor.
 *
 * Cancellation cannot abort an already-admitted synchronous Binder transaction. Such a call may
 * finish late (or never), so the worker is never replaced and new stops are rejected while occupied.
 * Every subsequent stage checks cancellation/deadline. Callers must serialize enable/stop decisions
 * and block enable while hasPendingCalls() is true, including after an unconfirmed timeout. A false
 * guard means the worker has finished its calls, NOT that Binder death has been confirmed. A process
 * restart loses this in-process guard; it cannot undo side effects already sent to a remote process.
 */
final class UserServiceStopper {
    private static final long DEFAULT_TIMEOUT_MILLIS = 4000L;
    private static final int DEFAULT_MAX_POLLS = 40;
    private static final long DEFAULT_POLL_MILLIS = 100L;
    private static final AtomicReference<Operation> ACTIVE = new AtomicReference<>();
    // ACTIVE rejects work behind unfinished calls. One bounded handoff slot bridges the previous
    // runnable's completion -> executor-idle transition, avoiding spurious "busy" on immediate reuse.
    // There is still only one RPC worker process-wide; a hung worker is never replaced.
    private static final ThreadPoolExecutor RPC_WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "user-service-stop-rpc");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    interface Endpoint {
        String disable() throws Exception;

        void destroy() throws Exception;

        boolean isAlive() throws Exception;
    }

    interface Removal {
        void remove() throws Exception;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final class Result {
        final boolean stopped;
        final String detail;

        Result(boolean stopped, String detail) {
            this.stopped = stopped;
            this.detail = detail;
        }
    }

    private UserServiceStopper() {
    }

    static Result stop(Endpoint endpoint, Removal removal) {
        return stop(endpoint, removal, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Positive total budget in milliseconds; fast timeout injection does not create another pool. */
    static Result stop(Endpoint endpoint, Removal removal, long timeoutMillis) {
        return stop(endpoint, removal, timeoutMillis, DEFAULT_MAX_POLLS,
                DEFAULT_POLL_MILLIS, Thread::sleep);
    }

    /** Legacy polling seam retained; its RPCs are now covered by the same default total deadline. */
    static Result stop(Endpoint endpoint, Removal removal, int maxPolls, long pollMillis,
            Sleeper sleeper) {
        return stop(endpoint, removal, DEFAULT_TIMEOUT_MILLIS, maxPolls, pollMillis, sleeper);
    }

    static Result stop(Endpoint endpoint, Removal removal, long timeoutMillis, int maxPolls,
            long pollMillis, Sleeper sleeper) {
        return stop(endpoint, removal, timeoutMillis, maxPolls, pollMillis, sleeper, System::nanoTime);
    }

    // Internal clock seam allows total-budget tests to advance time without timing races.
    static Result stop(Endpoint endpoint, Removal removal, long timeoutMillis, int maxPolls,
            long pollMillis, Sleeper sleeper, LongSupplier nanoTime) {
        if (timeoutMillis <= 0 || timeoutMillis > DEFAULT_TIMEOUT_MILLIS
                || maxPolls < 1 || pollMillis < 0) {
            throw new IllegalArgumentException("timeout must be 1..4000ms, with positive polls");
        }
        Operation operation = new Operation(timeoutMillis, nanoTime);
        if (Thread.currentThread().isInterrupted()) {
            return new Result(false, "停止未确认：监督线程已被中断，未发起调用");
        }
        if (!ACTIVE.compareAndSet(null, operation)) {
            return new Result(false, "停止未确认：前一次停止调用尚未结束，未发起重复调用");
        }
        try {
            RPC_WORKER.execute(() -> operation.run(endpoint, removal, maxPolls, pollMillis, sleeper));
        } catch (RejectedExecutionException rejected) {
            ACTIVE.compareAndSet(operation, null);
            return new Result(false, "停止未确认：停止工作线程仍忙，未发起调用");
        } catch (RuntimeException failure) {
            ACTIVE.compareAndSet(operation, null);
            return new Result(false, "停止未确认：无法启动停止工作线程：" + compactError(failure));
        }
        return operation.await();
    }

    /** Stays true after timeout/interruption until the actual worker finishes all admitted work. */
    static boolean hasPendingCalls() {
        return ACTIVE.get() != null;
    }

    private static Result performStop(Operation operation, Endpoint endpoint, Removal removal,
            int maxPolls, long pollMillis, Sleeper sleeper) throws Exception {
        List<String> warnings = new ArrayList<>();

        operation.checkpoint("停用");
        try {
            String response = endpoint.disable();
            if (response != null && (response.startsWith("错误") || response.contains("失败"))) {
                warnings.add("停用回报：" + response);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            warnings.add("停用调用：" + compactError(e));
        }

        operation.checkpoint("管理器移除");
        try {
            removal.remove();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            warnings.add("管理器移除：" + compactError(e));
        }

        boolean alive = readAlive(operation, endpoint);
        int gracefulPolls = Math.max(1, maxPolls / 2);
        for (int i = 0; alive && i < gracefulPolls; i++) {
            // Reserve the second half of the ORIGINAL budget for destroy and death confirmation.
            if (operation.remainingNanos() <= operation.budgetNanos / 2) break;
            operation.pause(sleeper, pollMillis);
            alive = readAlive(operation, endpoint);
        }

        if (alive) {
            operation.checkpoint("强制退出");
            try {
                endpoint.destroy();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                warnings.add("强制退出：" + compactError(e));
            }
            alive = readAlive(operation, endpoint);
        }

        for (int i = gracefulPolls; alive && i < maxPolls; i++) {
            operation.pause(sleeper, pollMillis);
            alive = readAlive(operation, endpoint);
        }

        operation.checkpoint("完成确认");
        if (alive) {
            return new Result(false, "停止未确认：UserService 仍存活"
                    + appendWarnings(warnings));
        }
        return new Result(true, "已确认 UserService 进程退出" + appendWarnings(warnings));
    }

    private static boolean readAlive(Operation operation, Endpoint endpoint) throws Exception {
        operation.checkpoint("Binder 存活检查");
        boolean alive = endpoint.isAlive();
        operation.checkpoint("Binder 存活检查完成");
        return alive;
    }

    private static final class Cancelled extends Exception { }

    private static final class Operation {
        final LongSupplier nanoTime;
        final long startedNanos;
        final long budgetNanos;
        final long timeoutMillis;
        volatile boolean cancelled;
        volatile String stage = "等待工作线程";
        // The fields below are guarded by this; no RPC is ever called under this monitor.
        Thread worker;
        Result result;
        boolean finished;

        Operation(long timeoutMillis, LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            this.startedNanos = nanoTime.getAsLong();
            this.timeoutMillis = timeoutMillis;
            budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        long remainingNanos() {
            return budgetNanos - (nanoTime.getAsLong() - startedNanos);
        }

        void checkpoint(String nextStage) throws Cancelled, InterruptedException {
            if (cancelled || remainingNanos() <= 0) throw new Cancelled();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            stage = nextStage;
        }

        void pause(Sleeper sleeper, long millis) throws Cancelled, InterruptedException {
            checkpoint("等待 Binder 死亡");
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos()));
            sleeper.sleep(Math.min(millis, remainingMillis));
            checkpoint("等待 Binder 死亡完成");
        }

        void run(Endpoint endpoint, Removal removal, int maxPolls, long pollMillis, Sleeper sleeper) {
            synchronized (this) {
                worker = Thread.currentThread();
            }
            Result outcome = new Result(false, "停止未确认：停止工作线程异常退出");
            try {
                outcome = performStop(this, endpoint, removal, maxPolls, pollMillis, sleeper);
            } catch (Cancelled ignored) {
                outcome = timedOut();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outcome = new Result(false, "停止未确认：停止工作线程被中断");
            } catch (Exception failure) {
                outcome = new Result(false, "停止未确认：" + stage + "：" + compactError(failure));
            } finally {
                synchronized (this) {
                    // Publish completion only after all user-supplied calls have really returned.
                    // Future cancellation is deliberately NOT used as a completion signal.
                    if (cancelled || remainingNanos() <= 0) outcome = timedOut();
                    worker = null;
                    ACTIVE.compareAndSet(this, null);
                    result = outcome;
                    finished = true;
                    notifyAll();
                }
            }
        }

        synchronized Result await() {
            while (!finished) {
                long remaining = remainingNanos();
                if (remaining <= 0) {
                    cancel();
                    return timedOut();
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    cancel();
                    Thread.currentThread().interrupt();
                    return new Result(false, "停止未确认：监督线程被中断；已发出的调用可能仍未结束");
                }
            }
            return result;
        }

        // Called only while holding this monitor, so a reused worker cannot be interrupted late.
        private void cancel() {
            cancelled = true;
            if (worker != null) worker.interrupt();
        }

        private Result timedOut() {
            return new Result(false, "停止未确认：总截止时间 " + timeoutMillis + " 毫秒已到（"
                    + stage + "）；已发出的调用可能仍未结束，请等待工作线程释放后再操作");
        }
    }

    private static String appendWarnings(List<String> warnings) {
        return warnings.isEmpty() ? "" : "\n附加信息：" + String.join("；", warnings);
    }

    private static String compactError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
