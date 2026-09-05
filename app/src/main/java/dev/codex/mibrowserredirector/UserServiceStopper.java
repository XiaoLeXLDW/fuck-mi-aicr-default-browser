package dev.codex.mibrowserredirector;

import java.util.ArrayList;
import java.util.List;

final class UserServiceStopper {
    private static final int DEFAULT_MAX_POLLS = 40;
    private static final long DEFAULT_POLL_MILLIS = 100L;

    interface Endpoint {
        String disable() throws Exception;

        void destroy() throws Exception;

        boolean isAlive();
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
        return stop(endpoint, removal, DEFAULT_MAX_POLLS, DEFAULT_POLL_MILLIS, Thread::sleep);
    }

    static Result stop(Endpoint endpoint, Removal removal, int maxPolls, long pollMillis,
            Sleeper sleeper) {
        List<String> warnings = new ArrayList<>();

        try {
            String response = endpoint.disable();
            if (response != null && (response.startsWith("错误") || response.contains("失败"))) {
                warnings.add("停用回报：" + response);
            }
        } catch (Exception e) {
            warnings.add("停用调用：" + compactError(e));
        }

        try {
            removal.remove();
        } catch (Exception e) {
            warnings.add("管理器移除：" + compactError(e));
        }

        boolean alive = endpoint.isAlive();
        int gracefulPolls = Math.max(1, maxPolls / 2);
        for (int i = 0; alive && i < gracefulPolls; i++) {
            try {
                sleeper.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnings.add("等待退出被中断");
                break;
            }
            alive = endpoint.isAlive();
        }

        if (alive) {
            try {
                endpoint.destroy();
            } catch (Exception e) {
                warnings.add("强制退出：" + compactError(e));
            }
        }

        for (int i = gracefulPolls; alive && i < maxPolls; i++) {
            try {
                sleeper.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnings.add("等待退出被中断");
                break;
            }
            alive = endpoint.isAlive();
        }

        if (alive) {
            return new Result(false, "停止失败：UserService 在 4 秒后仍存活"
                    + appendWarnings(warnings));
        }
        return new Result(true, "已确认 UserService 进程退出" + appendWarnings(warnings));
    }

    private static String appendWarnings(List<String> warnings) {
        return warnings.isEmpty() ? "" : "\n附加信息：" + String.join("；", warnings);
    }

    private static String compactError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
