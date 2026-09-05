package dev.codex.mibrowserredirector;

import android.app.IActivityController;
import android.content.Intent;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class RedirectorUserService extends IRedirectorService.Stub {
    private static final String XIAOMI_BROWSER = "com.android.browser";
    private final RedirectDispatcher redirectDispatcher = new RedirectDispatcher(
            new AmProcessLauncher(), new RedirectDispatcher.Listener() {
                @Override public void configured(RedirectDispatcher.Snapshot config) {
                    lastEvent = eventPrefix() + (config.enabled
                            ? "已注册控制器，等待 com.android.browser（"
                                + (config.observeOnly ? "观察模式" : "接管模式") + "）"
                            : "已停止接收新跳转，正在清理控制器");
                }
                @Override public void accepted(RedirectDispatcher.Snapshot config, String url) {
                    lastEvent = eventPrefix() + "已拦截，准备交给 " + config.target
                            + " → " + UrlExtractor.displayHost(url);
                }
                @Override public void finished(RedirectDispatcher.Snapshot config, String url,
                        RedirectDispatcher.Result result) {
                    onLaunchFinished(config, url, result);
                }
            });

    private volatile boolean enabled;
    private volatile boolean observeOnly;
    private volatile String targetPackage;
    private volatile String lastEvent = ServiceIdentity.BUILD_LABEL + "：尚未捕获启动事件";
    private volatile SystemActivityController systemController;
    private volatile Controller callback;

    public RedirectorUserService() {
    }

    @Override
    public synchronized String enable(String requestedTargetPackage, boolean requestedObserveOnly) {
        if (!isSafePackageName(requestedTargetPackage)) {
            return "错误：目标浏览器包名无效";
        }
        if (XIAOMI_BROWSER.equals(requestedTargetPackage)) {
            return "错误：目标不能仍是小米浏览器";
        }

        // Invalidate before set(): the system may synchronously wait on callbacks.
        // activityStarting never takes this service monitor or waits on dispatch.
        enabled = false;
        redirectDispatcher.disable();
        targetPackage = requestedTargetPackage;
        observeOnly = requestedObserveOnly;
        try {
            if (systemController == null) systemController = SystemActivityController.connect();
            if (callback == null) callback = new Controller();
            systemController.set(callback);
            redirectDispatcher.configure(requestedTargetPackage, requestedObserveOnly);
            enabled = true;
            return (observeOnly ? "观察模式已开启" : "接管模式已开启") + " → "
                    + requestedTargetPackage + "\n服务：" + ServiceIdentity.BUILD_LABEL
                    + "\n接口：" + systemController.implementationName();
        } catch (SecurityException e) {
            enabled = false;
            return "错误：Shell 没有 SET_ACTIVITY_WATCHER 权限\n" + compactError(e);
        } catch (Throwable e) {
            enabled = false;
            return "错误：无法注册 Activity Controller\n" + compactError(e);
        }
    }

    @Override
    public synchronized String disable() {
        enabled = false;
        redirectDispatcher.disable();
        String result = "已停用";
        if (systemController != null) {
            try {
                systemController.set(null);
            } catch (Throwable e) {
                result = "停用时清理控制器失败：" + compactError(e);
            }
        }
        callback = null;
        systemController = null;
        targetPackage = null;
        observeOnly = false;
        lastEvent = eventPrefix() + result;
        return result;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getState() {
        String target = targetPackage == null ? "未选择" : targetPackage;
        String api = systemController == null ? "未连接" : systemController.implementationName();
        return "服务版本：" + ServiceIdentity.BUILD_LABEL
                + "（协议 " + ServiceIdentity.PROTOCOL_VERSION + "）"
                + "\n服务代：" + ServiceIdentity.USER_SERVICE_GENERATION
                + "\n控制器：" + (enabled ? "运行中" : "已停用")
                + "\n模式：" + (observeOnly ? "观察（始终放行）" : "接管")
                + "\n目标：" + target
                + "\n系统接口：" + api
                + "\nUserService UID/PID：" + Process.myUid() + "/" + Process.myPid();
    }

    @Override
    public String getLastEvent() {
        return lastEvent;
    }

    @Override
    public int getProtocolVersion() {
        return ServiceIdentity.PROTOCOL_VERSION;
    }

    @Override
    public int getServiceGeneration() {
        return ServiceIdentity.USER_SERVICE_GENERATION;
    }

    @Override
    public void destroy() {
        disable();
        redirectDispatcher.close();
        try { redirectDispatcher.awaitTermination(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.exit(0);
    }

    private final class Controller extends IActivityController.Stub {
        @Override
        public boolean activityStarting(Intent intent, String targetPackageName) {
            RedirectDispatcher.Snapshot config = redirectDispatcher.snapshot();
            try {
                if (!config.enabled || !XIAOMI_BROWSER.equals(targetPackageName)) return true;
                String selected = config.target;
                if (!isSafePackageName(selected)) return true;

                if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
                    recordCallbackEvent(config, "捕获到小米浏览器，但 action 不是 VIEW；已放行");
                    return true;
                }

                String dataString = intent.getDataString();
                if (dataString == null) {
                    recordCallbackEvent(config, "捕获到小米浏览器，但 data 为空；已放行");
                    return true;
                }

                String url = UrlExtractor.extract(dataString);
                if (url == null) {
                    recordCallbackEvent(config, "捕获到小米浏览器，但 data 不是可用的 HTTP/HTTPS 网址；已放行");
                    return true;
                }

                if (config.observeOnly) {
                    recordCallbackEvent(config, "观察到可接管网址 → " + UrlExtractor.displayHost(url)
                            + "；本次仍放行小米浏览器");
                    return true;
                }

                RedirectDispatcher.Submission submission = redirectDispatcher.trySubmit(config, url);
                if (submission == RedirectDispatcher.Submission.DUPLICATE) {
                    // Preserve the meaningful accepted/completed diagnostic.
                    return false;
                }

                if (submission == RedirectDispatcher.Submission.REJECTED) {
                    recordCallbackEvent(config, "重定向队列不可用；已放行小米浏览器");
                    return true;
                }
                return false;
            } catch (Throwable e) {
                recordCallbackEvent(config, "回调异常，已放行：" + e.getClass().getSimpleName());
                return true;
            }
        }

        @Override
        public boolean activityResuming(String pkg) {
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            return true;
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            return 0;
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats) {
            return 0;
        }

        @Override
        public int systemNotResponding(String msg) {
            return -1;
        }
    }

    private void recordCallbackEvent(RedirectDispatcher.Snapshot config, String message) {
        redirectDispatcher.tryRecord(config, () -> lastEvent = eventPrefix() + message);
    }

    private void onLaunchFinished(RedirectDispatcher.Snapshot config, String url,
            RedirectDispatcher.Result result) {
        switch (result) {
            case SUCCEEDED:
                lastEvent = eventPrefix() + "已打开 " + config.target + " → " + UrlExtractor.displayHost(url);
                break;
            case TIMED_OUT:
                lastEvent = eventPrefix() + "启动 " + config.target + " 超时；可立即重试";
                break;
            case CANCELLED:
                lastEvent = eventPrefix() + "启动任务已取消";
                break;
            default:
                lastEvent = eventPrefix() + "am start 未确认成功 → " + config.target + "；可立即重试";
        }
    }

    private static boolean isSafePackageName(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private static String compactError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static String eventPrefix() {
        return now() + " [" + ServiceIdentity.BUILD_LABEL + "] ";
    }
}
