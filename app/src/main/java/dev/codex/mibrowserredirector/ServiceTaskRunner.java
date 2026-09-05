package dev.codex.mibrowserredirector;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-scoped, bounded lanes. A stalled state/enable RPC cannot consume the stop lane. */
final class ServiceTaskRunner implements AutoCloseable {
    private final ThreadPoolExecutor io = lane("redirector-ui-io", 8);
    private final ThreadPoolExecutor stop = lane("redirector-ui-stop", 1);

    void executeIo(Runnable task) {
        io.execute(task);
    }

    void executeStop(Runnable task) {
        stop.execute(task);
    }

    private static ThreadPoolExecutor lane(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), action -> {
                    Thread thread = new Thread(action, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void close() {
        io.shutdownNow();
        stop.shutdownNow();
    }
}
