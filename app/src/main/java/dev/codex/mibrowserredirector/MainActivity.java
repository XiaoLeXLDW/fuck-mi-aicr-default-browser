package dev.codex.mibrowserredirector;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.text.Collator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendId;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendSnapshot;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.Preference;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceSession;

public final class MainActivity extends Activity {
    private static final int PRIVILEGE_PERMISSION_REQUEST = 6101;
    private static final AtomicInteger GLOBAL_OPERATION_EPOCH = new AtomicInteger();
    private static final AtomicInteger GLOBAL_STOP_EPOCH = new AtomicInteger();
    private static final String PREFS = "redirector";
    private static final String PREF_BROWSER = "browser_package";
    private static final String PREF_BROWSER_SELECTION = "selected_browser_package";
    private static final String PREF_OBSERVE_ONLY = "observe_only";
    private static final String PREF_DESIRED_ENABLED = "desired_enabled";
    private static final String PREF_BACKEND = "privilege_backend";
    private static final String PREF_ACTIVE_BACKEND = "active_privilege_backend";
    private static final String PREF_VERIFICATION_TOKEN = "stellar_verification_token";
    private static final long CONNECTION_TIMEOUT_MILLIS = 8_000L;

    private enum LifecycleState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<BrowserOption> browsers = new ArrayList<>();

    private Spinner backendSpinner;
    private BrowserPicker browserPicker;
    private TextView statusText;
    private TextView eventText;
    private Button enableButton;
    private Button disableButton;
    private Switch observeSwitch;
    private SharedPreferences preferences;
    private PrivilegeRuntime privilegeRuntime;

    private volatile IRedirectorService remoteService;
    private volatile IBinder remoteBinder;
    private ServiceSession serviceSession;
    private BackendId operationBackend;
    private LifecycleState lifecycleState = LifecycleState.STOPPED;
    private boolean connectionPending;
    private int connectionToken;
    private boolean identityRetryAttempted;
    private boolean activityDestroyed;
    private String pendingEnablePackage;
    private boolean pendingEnableObserveOnly;
    private boolean pendingStop;
    private boolean stopWorkerRunning;
    private int operationEpoch;
    private int pendingPermissionRequestCode = -1;

    private final PrivilegeRuntime.Listener privilegeListener =
            new PrivilegeRuntime.Listener() {
                @Override
                public void onAvailabilityChanged(BackendId backendId, boolean available) {
                    mainHandler.post(() -> {
                        if (activityDestroyed || lifecycleState == LifecycleState.STOPPING) return;
                        if (!available && backendId == operationBackend
                                && remoteService == null && connectionPending) {
                            connectionPending = false;
                            connectionToken++;
                            ServiceSession interruptedSession = serviceSession;
                            serviceSession = null;
                            if (interruptedSession != null) interruptedSession.detach();
                            lifecycleState = LifecycleState.ERROR;
                            showStatus(backendId.displayName()
                                    + " 在连接期间断开；已保留原后端，等待服务恢复");
                            setButtonsEnabled(true);
                            return;
                        }
                        refreshState();
                    });
                }

                @Override
                public void onPermissionResult(BackendId backendId, int requestCode,
                        boolean allowed) {
                    mainHandler.post(() -> handlePermissionResult(
                            backendId, requestCode, allowed));
                }
            };

    private final PrivilegeRuntime.ServiceCallback userServiceCallback =
            new PrivilegeRuntime.ServiceCallback() {
                @Override
                public void onServiceConnected(ServiceSession session, IBinder binder) {
                    if (serviceSession != session) return;
                    if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
                    connectionPending = false;
                    connectionToken++;
                    operationBackend = session.backendId();
                    verifyConnectedService(session, binder);
                }

                @Override
                public void onServiceDisconnected(ServiceSession session) {
                    if (serviceSession != session) return;
                    if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
                    if (lifecycleState == LifecycleState.STOPPING && pendingStop
                            && !stopWorkerRunning
                            && remoteService != null && remoteBinder != null) {
                        connectionPending = false;
                        connectionToken++;
                        stopConnectedService(remoteService, remoteBinder);
                        return;
                    }
                    session.detach();
                    remoteService = null;
                    remoteBinder = null;
                    serviceSession = null;
                    connectionPending = false;
                    connectionToken++;
                    if (lifecycleState == LifecycleState.STOPPING) {
                        if (!stopWorkerRunning) {
                            pendingStop = false;
                            lifecycleState = LifecycleState.ERROR;
                            showStatus("停止未确认：连接已断开，但没有可核验的 UserService Binder");
                            setButtonsEnabled(true);
                            shutdownExecutorIfDestroyed();
                        }
                        return;
                    }
                    lifecycleState = LifecycleState.STOPPED;
                    if (preferences.getBoolean(PREF_DESIRED_ENABLED, false)) {
                        showStatus("UserService 意外断开，正在等待原后端恢复…");
                        mainHandler.postDelayed(MainActivity.this::refreshState, 500L);
                    } else {
                        refreshState();
                    }
                }

                @Override
                public void onServiceStartFailed(ServiceSession session, int errorCode,
                        String message) {
                    if (serviceSession != session) return;
                    if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
                    session.detach();
                    connectionPending = false;
                    connectionToken++;
                    serviceSession = null;
                    if (errorCode == -2 && !identityRetryAttempted) {
                        identityRetryAttempted = true;
                        showStatus("已清理身份不匹配的旧服务，正在重新连接…");
                        mainHandler.postDelayed(MainActivity.this::bindForPendingAction, 250L);
                        return;
                    }
                    lifecycleState = LifecycleState.ERROR;
                    boolean wasStopping = pendingStop;
                    if (!wasStopping) {
                        preferences.edit()
                                .putBoolean(PREF_DESIRED_ENABLED, false)
                                .remove(PREF_ACTIVE_BACKEND)
                                .apply();
                        operationBackend = null;
                    }
                    clearPendingActions();
                    showStatus((wasStopping ? "停止未确认" : "UserService 启动失败")
                            + "（" + errorCode + "）：" + message);
                    shutdownExecutorIfDestroyed();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateLegacyBackendState();
        privilegeRuntime = PrivilegeRuntime.create(
                this, mainHandler, getOrCreateVerificationToken());

        backendSpinner = findViewById(R.id.backend_spinner);
        statusText = findViewById(R.id.status_text);
        eventText = findViewById(R.id.event_text);
        enableButton = findViewById(R.id.enable_button);
        disableButton = findViewById(R.id.disable_button);
        observeSwitch = findViewById(R.id.observe_switch);
        Button refreshButton = findViewById(R.id.refresh_button);

        ArrayAdapter<Preference> backendAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, Preference.values());
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backendSpinner.setAdapter(backendAdapter);
        backendSpinner.setSelection(preferenceIndex(savedBackendPreference()));

        browserPicker = new BrowserPicker(this, findViewById(R.id.browser_picker), option -> {
            // A UI draft is separate from PREF_BROWSER, which a reconnect may apply to the service.
            preferences.edit().putString(PREF_BROWSER_SELECTION, option.packageName).apply();
            setButtonsEnabled(lifecycleState != LifecycleState.STARTING
                    && lifecycleState != LifecycleState.STOPPING);
        });
        observeSwitch.setChecked(preferences.getBoolean(PREF_OBSERVE_ONLY, true));

        enableButton.setOnClickListener(view -> requestEnable());
        disableButton.setOnClickListener(view -> requestDisable());
        refreshButton.setOnClickListener(view -> {
            persistSelectedBackendPreferenceIfIdle();
            loadBrowsers();
            refreshState();
        });

        privilegeRuntime.startObserving(privilegeListener);
        loadBrowsers();
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBrowsers();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        browserPicker.close();
        privilegeRuntime.close();
        boolean keepBoundedStopWorker = lifecycleState == LifecycleState.STOPPING
                && stopWorkerRunning
                && GLOBAL_STOP_EPOCH.get() == operationEpoch;
        if (!keepBoundedStopWorker) {
            abandonOperation();
            connectionPending = false;
            connectionToken++;
            ServiceSession session = serviceSession;
            if (session != null) session.detach();
            serviceSession = null;
            remoteService = null;
            remoteBinder = null;
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void loadBrowsers() {
        PackageManager pm = getPackageManager();
        Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> handlers = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_ALL);
        Set<String> seen = new HashSet<>();
        List<BrowserOption> found = new ArrayList<>();
        for (ResolveInfo info : handlers) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || "com.android.browser".equals(packageName)) {
                continue;
            }
            if (!seen.add(packageName)) continue;
            CharSequence label = pm.getApplicationLabel(info.activityInfo.applicationInfo);
            found.add(new BrowserOption(label == null ? packageName : label.toString(), packageName));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        found.sort((left, right) -> {
            int byLabel = collator.compare(left.label, right.label);
            return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
        });
        String selected = preferences.getString(PREF_BROWSER_SELECTION,
                preferences.getString(PREF_BROWSER, null));
        browsers.clear();
        browsers.addAll(found);
        ResolveInfo defaultHandler = pm.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
        String defaultPackage = defaultHandler != null && defaultHandler.activityInfo != null
                ? defaultHandler.activityInfo.packageName : null;
        browserPicker.submitOptions(browsers, selected, defaultPackage);
        if (selected == null && browserPicker.getSelection() != null) {
            preferences.edit().putString(PREF_BROWSER_SELECTION,
                    browserPicker.getSelection().packageName).apply();
        }
        setButtonsEnabled(lifecycleState != LifecycleState.STARTING
                && lifecycleState != LifecycleState.STOPPING);
        if (browsers.isEmpty()) {
            eventText.setText("没有发现可处理 HTTPS 的第三方浏览器。请先安装浏览器，再点“刷新状态”。");
        }
    }

    private void requestEnable() {
        if (isAnotherStopWorkerRunning()) {
            showStatus("停服任务仍在后台确认 Binder 死亡，请等待最多 4 秒");
            return;
        }
        if (lifecycleState == LifecycleState.STOPPING) {
            showStatus("正在停止服务，请等待最多 12 秒");
            return;
        }
        BrowserOption option = browserPicker.getSelection();
        if (option == null) {
            showStatus("请先安装并选择一个浏览器");
            return;
        }
        persistSelectedBackendPreferenceIfIdle();
        claimOperation();
        stopWorkerRunning = false;
        pendingEnablePackage = option.packageName;
        pendingEnableObserveOnly = observeSwitch.isChecked();
        pendingStop = false;
        identityRetryAttempted = false;
        operationBackend = activeBackendFromPreferences();
        preferences.edit()
                .putString(PREF_BROWSER, option.packageName)
                .putBoolean(PREF_OBSERVE_ONLY, pendingEnableObserveOnly)
                .putBoolean(PREF_DESIRED_ENABLED, true)
                .apply();
        runPendingAction();
    }

    private void requestDisable() {
        if (isAnotherStopWorkerRunning()) {
            showStatus("停服任务已在另一个页面实例中运行，请等待最多 4 秒");
            return;
        }
        if (lifecycleState == LifecycleState.STOPPING) {
            showStatus("停止流程已在运行，请等待最多 12 秒");
            return;
        }
        preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).commit();
        claimOperation();
        pendingStop = true;
        pendingEnablePackage = null;
        identityRetryAttempted = false;
        operationBackend = serviceSession == null
                ? activeBackendFromPreferences()
                : serviceSession.backendId();
        lifecycleState = LifecycleState.STOPPING;
        setButtonsEnabled(false);
        showStatus("停止第 1/3 步：正在连接原后端的服务身份…");
        runPendingAction();
    }

    private void runPendingAction() {
        if (pendingEnablePackage == null && !pendingStop) return;
        // An already-delivered UserService Binder is sufficient to disable the
        // controller and force process death. Manager availability/permission
        // is only needed when a session must be (re)bound.
        if (pendingStop && remoteService != null && remoteBinder != null) {
            stopConnectedService(remoteService, remoteBinder);
            return;
        }
        BackendSnapshot backend = resolveOperationBackend();
        if (backend == null) {
            finishUnavailableOperation();
            return;
        }
        if (privilegeRuntime.hasAmbiguousShizukuSource(backend.id())) {
            finishAmbiguousShizukuOperation();
            return;
        }
        if (!ensurePrivilegePermission(backend)) return;
        if (remoteService == null || remoteBinder == null) {
            bindForPendingAction();
            return;
        }
        if (pendingStop) {
            stopConnectedService(remoteService, remoteBinder);
        } else {
            executeEnable();
        }
    }

    private void bindForPendingAction() {
        if (activityDestroyed || !ownsOperation(operationEpoch)) return;
        if (connectionPending || (pendingEnablePackage == null && !pendingStop)) return;
        BackendId backendId = operationBackend;
        if (backendId == null) {
            finishUnavailableOperation();
            return;
        }
        connectionPending = true;
        int epoch = operationEpoch;
        int token = ++connectionToken;
        if (!pendingStop) lifecycleState = LifecycleState.STARTING;
        preferences.edit()
                .putString(PREF_ACTIVE_BACKEND, backendId.storageValue())
                .apply();
        setButtonsEnabled(false);
        showStatus(pendingStop
                ? "停止第 1/3 步：正在通过 " + backendId.displayName() + " 取得服务身份…"
                : "正在通过 " + backendId.displayName() + " 启动 "
                        + ServiceIdentity.BUILD_LABEL + "…");

        ServiceSession requestedSession;
        try {
            requestedSession = privilegeRuntime.bind(backendId, userServiceCallback);
        } catch (RuntimeException error) {
            connectionPending = false;
            finishOperationFailure("UserService 绑定失败：" + compactError(error));
            return;
        }
        serviceSession = requestedSession;
        mainHandler.postDelayed(() -> {
            if (!ownsOperation(epoch) || !connectionPending || token != connectionToken
                    || serviceSession != requestedSession) return;
            connectionPending = false;
            connectionToken++;
            boolean wasStopping = pendingStop;
            try {
                requestedSession.remove();
            } catch (Exception ignored) {
                // No Binder was delivered, so process death cannot be confirmed here.
            }
            requestedSession.detach();
            if (serviceSession == requestedSession) serviceSession = null;
            clearPendingActions();
            lifecycleState = LifecycleState.ERROR;
            showStatus(wasStopping
                    ? "停止未确认：8 秒内没有取得 UserService Binder"
                    : "UserService 连接超时（8 秒）；已请求原后端清理");
            shutdownExecutorIfDestroyed();
        }, CONNECTION_TIMEOUT_MILLIS);
    }

    private void verifyConnectedService(ServiceSession session, IBinder binder) {
        IRedirectorService candidate = IRedirectorService.Stub.asInterface(binder);
        int epoch = operationEpoch;
        submitIo(() -> {
            int protocol = -1;
            int serviceGeneration = -1;
            String failure = null;
            try {
                protocol = candidate.getProtocolVersion();
                serviceGeneration = candidate.getServiceGeneration();
            } catch (RemoteException | RuntimeException e) {
                failure = compactError(e);
            }
            int detectedProtocol = protocol;
            int detectedGeneration = serviceGeneration;
            String detectedFailure = failure;
            mainHandler.post(() -> finishServiceVerification(
                    epoch,
                    session,
                    candidate,
                    binder,
                    detectedProtocol,
                    detectedGeneration,
                    detectedFailure
            ));
        });
    }

    private void finishServiceVerification(int epoch, ServiceSession session,
            IRedirectorService candidate, IBinder binder, int protocol,
            int serviceGeneration, String failure) {
        if (!ownsOperation(epoch) || serviceSession != session) return;
        if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
        boolean matches = protocol == ServiceIdentity.PROTOCOL_VERSION
                && serviceGeneration == ServiceIdentity.USER_SERVICE_GENERATION;
        if (!matches) {
            String reason = failure == null
                    ? "协议/服务代=" + protocol + "/" + serviceGeneration
                    : failure;
            showStatus("拒绝使用旧 UserService（" + reason + "），正在退出它…");
            stopSpecificService(candidate, binder, session, result -> {
                if (!ownsOperation(epoch)) return;
                if (!result.stopped) {
                    restoreFailedStop(candidate, binder, session);
                    finishOperationFailure("旧 UserService 无法退出\n" + result.detail);
                } else if (!identityRetryAttempted) {
                    session.detach();
                    if (serviceSession == session) serviceSession = null;
                    identityRetryAttempted = true;
                    mainHandler.postDelayed(this::bindForPendingAction, 250L);
                } else {
                    finishOperationFailure("连续两次取得不兼容的 UserService，已停止重试");
                }
            });
            return;
        }

        identityRetryAttempted = false;
        remoteService = candidate;
        remoteBinder = binder;
        operationBackend = session.backendId();
        if (pendingStop) {
            stopConnectedService(candidate, binder);
        } else if (pendingEnablePackage != null) {
            executeEnable();
        } else if (preferences.getBoolean(PREF_DESIRED_ENABLED, false)) {
            restorePendingEnable();
            runPendingAction();
        } else {
            pendingStop = true;
            lifecycleState = LifecycleState.STOPPING;
            stopConnectedService(candidate, binder);
        }
    }

    private void executeEnable() {
        IRedirectorService service = remoteService;
        IBinder binder = remoteBinder;
        ServiceSession session = serviceSession;
        String target = pendingEnablePackage;
        if (service == null || binder == null || session == null || target == null) return;
        boolean observeOnly = pendingEnableObserveOnly;
        int epoch = operationEpoch;
        pendingEnablePackage = null;
        lifecycleState = LifecycleState.STARTING;
        setButtonsEnabled(false);
        showStatus("正在启用 " + ServiceIdentity.BUILD_LABEL + " 控制器…");
        submitIo(() -> {
            try {
                String result = service.enable(target, observeOnly);
                boolean enabled = service.isEnabled();
                mainHandler.post(() -> {
                    if (!ownsOperation(epoch) || service != remoteService
                            || session != serviceSession || activityDestroyed) return;
                    if (!enabled) {
                        cleanupFailedEnable(epoch, service, binder, session,
                                result == null || result.isEmpty()
                                        ? "UserService 未进入运行状态"
                                        : result);
                        return;
                    }
                    lifecycleState = LifecycleState.RUNNING;
                    showStatus(result);
                    setButtonsEnabled(true);
                    refreshRemoteState();
                });
            } catch (RemoteException | RuntimeException e) {
                mainHandler.post(() -> {
                    if (!ownsOperation(epoch) || service != remoteService
                            || session != serviceSession || activityDestroyed) return;
                    cleanupFailedEnable(epoch, service, binder, session,
                            "UserService 调用失败：" + compactError(e));
                });
            }
        });
    }

    private void cleanupFailedEnable(int epoch, IRedirectorService service, IBinder binder,
            ServiceSession session, String failure) {
        if (!ownsOperation(epoch) || service != remoteService
                || session != serviceSession || activityDestroyed) return;
        if (!beginGlobalStop(epoch)) {
            showStatus("另一个停服任务仍在运行；已保留当前服务供稍后清理");
            return;
        }
        preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
        remoteService = null;
        remoteBinder = null;
        lifecycleState = LifecycleState.STOPPING;
        stopWorkerRunning = true;
        setButtonsEnabled(false);
        showStatus("开启失败，正在清理 UserService…\n" + failure);
        stopSpecificService(service, binder, session, result -> {
            stopWorkerRunning = false;
            if (!ownsOperation(epoch)) {
                endGlobalStop(epoch);
                shutdownExecutorIfDestroyed();
                return;
            }
            if (!result.stopped) {
                restoreFailedStop(service, binder, session);
                lifecycleState = LifecycleState.ERROR;
                setButtonsEnabled(true);
                showStatus("开启失败，且清理未确认\n" + failure + "\n" + result.detail);
                endGlobalStop(epoch);
                shutdownExecutorIfDestroyed();
                return;
            }
            if (serviceSession == session) serviceSession = null;
            session.detach();
            operationBackend = null;
            preferences.edit().remove(PREF_ACTIVE_BACKEND).apply();
            lifecycleState = LifecycleState.ERROR;
            setButtonsEnabled(true);
            showStatus("开启失败；残留 UserService 已清理\n" + failure);
            endGlobalStop(epoch);
            shutdownExecutorIfDestroyed();
        });
    }

    private void stopConnectedService(IRedirectorService service, IBinder binder) {
        if (stopWorkerRunning) return;
        int epoch = operationEpoch;
        if (!ownsOperation(epoch)) return;
        if (!beginGlobalStop(epoch)) {
            showStatus("停服任务已在另一个页面实例中运行，请等待最多 4 秒");
            return;
        }
        ServiceSession stopSession = serviceSession;
        stopWorkerRunning = true;
        remoteService = null;
        remoteBinder = null;
        connectionPending = false;
        connectionToken++;
        lifecycleState = LifecycleState.STOPPING;
        setButtonsEnabled(false);
        showStatus("停止第 2/3 步：正在注销全局 Activity Controller…");
        stopSpecificService(service, binder, stopSession, result -> {
            stopWorkerRunning = false;
            if (!ownsOperation(epoch)) {
                endGlobalStop(epoch);
                shutdownExecutorIfDestroyed();
                return;
            }
            if (!result.stopped) {
                restoreFailedStop(service, binder, stopSession);
                finishOperationFailure(result.detail);
                endGlobalStop(epoch);
                return;
            }
            if (serviceSession == stopSession) serviceSession = null;
            if (stopSession != null) stopSession.detach();
            pendingStop = false;
            pendingEnablePackage = null;
            operationBackend = null;
            preferences.edit().remove(PREF_ACTIVE_BACKEND).apply();
            lifecycleState = LifecycleState.STOPPED;
            setButtonsEnabled(true);
            String message = "停止完成 [" + ServiceIdentity.BUILD_LABEL + "]\n"
                    + "第 3/3 步：已确认 UserService Binder 死亡，不会自动重连";
            showStatus(message);
            if (!activityDestroyed) eventText.setText(message);
            endGlobalStop(epoch);
            shutdownExecutorIfDestroyed();
        });
    }

    private void stopSpecificService(IRedirectorService service, IBinder binder,
            ServiceSession stopSession, StopCompletion completion) {
        submitIo(() -> {
            UserServiceStopper.Result result = performStop(service, binder, stopSession);
            mainHandler.post(() -> completion.complete(result));
        });
    }

    private UserServiceStopper.Result performStop(IRedirectorService service, IBinder binder,
            ServiceSession stopSession) {
        try (UserServiceBinderHelper.DeathMonitor deathMonitor =
                     UserServiceBinderHelper.watchDeath(binder)) {
            return UserServiceStopper.stop(
                    new UserServiceStopper.Endpoint() {
                        @Override
                        public String disable() throws Exception {
                            return service.disable();
                        }

                        @Override
                        public void destroy() throws Exception {
                            UserServiceBinderHelper.destroy(binder);
                        }

                        @Override
                        public boolean isAlive() {
                            return deathMonitor.isAlive();
                        }
                    },
                    () -> {
                        if (stopSession != null) stopSession.remove();
                    }
            );
        } catch (Throwable error) {
            return new UserServiceStopper.Result(false,
                    "停止流程异常：" + compactError(error));
        }
    }

    private void restoreFailedStop(IRedirectorService service, IBinder binder,
            ServiceSession stopSession) {
        if (!UserServiceBinderHelper.isAlive(binder)) return;
        remoteService = service;
        remoteBinder = binder;
        serviceSession = stopSession;
        if (stopSession != null) operationBackend = stopSession.backendId();
    }

    private BackendSnapshot resolveOperationBackend() {
        if (operationBackend != null) return privilegeRuntime.snapshot(operationBackend);
        BackendId pinned = activeBackendFromPreferences();
        BackendSnapshot selected = privilegeRuntime.choose(selectedBackendPreference(), pinned);
        if (selected != null) operationBackend = selected.id();
        return selected;
    }

    private boolean ensurePrivilegePermission(BackendSnapshot initialSnapshot) {
        BackendSnapshot backend = privilegeRuntime.snapshot(initialSnapshot.id());
        if (!backend.available()) {
            if (pendingStop) {
                lifecycleState = LifecycleState.ERROR;
                pendingStop = false;
                setButtonsEnabled(true);
                showStatus(backend.id().displayName()
                        + " 未运行；已记住“停止”状态，但目前无法核验残留进程");
            } else {
                showStatus(backend.id().displayName()
                        + " 未运行。请先在对应管理器中通过无线调试启动服务。");
            }
            return false;
        }
        if (backend.authorized()) return true;
        if (backend.permissionRationale()) {
            finishOperationFailure(backend.id().displayName()
                    + " 权限已被拒绝，请到管理器的授权应用列表中重新允许");
            return false;
        }
        showStatus("正在请求 " + backend.id().displayName() + " 授权…");
        try {
            pendingPermissionRequestCode = PRIVILEGE_PERMISSION_REQUEST
                    + (operationEpoch & 0x0000ffff);
            privilegeRuntime.requestPermission(
                    backend.id(), pendingPermissionRequestCode);
        } catch (Exception e) {
            pendingPermissionRequestCode = -1;
            finishOperationFailure(backend.id().displayName()
                    + " 授权请求失败：" + compactError(e));
        }
        return false;
    }

    private void handlePermissionResult(BackendId backendId, int requestCode,
            boolean allowed) {
        if (activityDestroyed || requestCode != pendingPermissionRequestCode
                || backendId != operationBackend || !ownsOperation(operationEpoch)) return;
        pendingPermissionRequestCode = -1;
        if (allowed) {
            showStatus(backendId.displayName() + " 已授权，正在继续…");
            runPendingAction();
        } else {
            if (pendingEnablePackage != null) {
                preferences.edit()
                        .putBoolean(PREF_DESIRED_ENABLED, false)
                        .remove(PREF_ACTIVE_BACKEND)
                        .apply();
            }
            clearPendingActions();
            operationBackend = null;
            lifecycleState = LifecycleState.ERROR;
            showStatus(backendId.displayName() + " 授权被拒绝");
        }
    }

    private void refreshState() {
        if (activityDestroyed) return;
        int globalStopEpoch = GLOBAL_STOP_EPOCH.get();
        if (globalStopEpoch != 0 && globalStopEpoch != operationEpoch) {
            lifecycleState = LifecycleState.STOPPING;
            showStatus("停服任务仍在后台确认 Binder 死亡…");
            setButtonsEnabled(false);
            mainHandler.postDelayed(this::refreshState, 500L);
            return;
        }
        if (globalStopEpoch == 0 && lifecycleState == LifecycleState.STOPPING
                && !stopWorkerRunning && !pendingStop) {
            lifecycleState = LifecycleState.ERROR;
        }
        if (lifecycleState == LifecycleState.STARTING
                || lifecycleState == LifecycleState.STOPPING
                || connectionPending) return;

        BackendId pinned = activeBackendFromPreferences();
        BackendSnapshot backend = privilegeRuntime.choose(selectedBackendPreference(), pinned);
        if (backend == null) {
            lifecycleState = LifecycleState.STOPPED;
            operationBackend = null;
            showStatus(noBackendStatus());
            setButtonsEnabled(true);
            return;
        }
        operationBackend = pinned == null ? null : backend.id();
        if (!backend.available()) {
            showStatus(backend.id().displayName()
                    + "：未运行\n控制器：未知/不可连接");
            setButtonsEnabled(true);
            return;
        }
        if (!backend.authorized()) {
            showStatus(backend.id().displayName() + "：运行中\n授权：尚未允许");
            setButtonsEnabled(true);
            return;
        }

        boolean desiredEnabled = preferences.getBoolean(PREF_DESIRED_ENABLED, false);
        if (remoteService != null) {
            if (desiredEnabled) {
                lifecycleState = LifecycleState.RUNNING;
                refreshRemoteState();
            } else {
                if (!ownsOperation(operationEpoch)) claimOperation();
                pendingStop = true;
                operationBackend = backend.id();
                lifecycleState = LifecycleState.STOPPING;
                runPendingAction();
            }
            return;
        }

        if (!desiredEnabled && pinned == null) {
            lifecycleState = LifecycleState.STOPPED;
            showStoppedState(backend);
            return;
        }

        operationBackend = backend.id();
        if (!desiredEnabled) {
            if (!ownsOperation(operationEpoch)) claimOperation();
            pendingStop = true;
            lifecycleState = LifecycleState.STOPPING;
            runPendingAction();
            return;
        }

        if (!ownsOperation(operationEpoch)) claimOperation();
        restorePendingEnable();
        if (pendingEnablePackage == null) {
            lifecycleState = LifecycleState.ERROR;
            preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
            showStatus("无法恢复接管：没有保存目标浏览器，请重新选择并开启");
            return;
        }
        runPendingAction();
    }

    private void restorePendingEnable() {
        pendingEnablePackage = preferences.getString(PREF_BROWSER, null);
        pendingEnableObserveOnly = preferences.getBoolean(PREF_OBSERVE_ONLY, true);
    }

    private void refreshRemoteState() {
        IRedirectorService service = remoteService;
        ServiceSession session = serviceSession;
        if (service == null || session == null || lifecycleState == LifecycleState.STOPPING) return;
        ioExecutor.execute(() -> {
            try {
                String state = service.getState();
                String event = service.getLastEvent();
                BackendSnapshot backend = privilegeRuntime.snapshot(session.backendId());
                mainHandler.post(() -> {
                    if (service != remoteService || session != serviceSession
                            || activityDestroyed) return;
                    statusText.setText("权限后端：" + session.backendId().displayName()
                            + "（服务 API " + backend.serverVersion() + "）\n" + state);
                    eventText.setText(event);
                });
            } catch (RemoteException | RuntimeException e) {
                mainHandler.post(() -> {
                    if (service != remoteService || session != serviceSession
                            || activityDestroyed) return;
                    lifecycleState = LifecycleState.ERROR;
                    showStatus("读取 UserService 状态失败：" + compactError(e)
                            + "\n仍保留原后端，可直接点“停用并退出服务”重试清理");
                    setButtonsEnabled(true);
                });
            }
        });
    }

    private void showStoppedState(BackendSnapshot backend) {
        showStatus("权限后端：" + backend.id().displayName()
                + "（服务 API " + backend.serverVersion() + "）\n"
                + "授权：已允许\n"
                + "控制器 " + ServiceIdentity.BUILD_LABEL + "：已停止\n"
                + "停止状态不会自动创建或重连 UserService");
        if (!activityDestroyed) {
            eventText.setText("选择后端后点“开启接管”。切换后端前必须先停用，"
                    + "停止按钮会等待 Binder 死亡再报告成功。");
        }
        setButtonsEnabled(true);
    }

    private String noBackendStatus() {
        List<BackendSnapshot> snapshots = privilegeRuntime.snapshots();
        StringBuilder text = new StringBuilder("没有可用的权限后端\n");
        for (BackendSnapshot snapshot : snapshots) {
            text.append(snapshot.id().displayName())
                    .append("：")
                    .append(snapshot.available() ? "已连接" : "未连接")
                    .append('\n');
        }
        return text.append("请先启动 Shizuku 或兼容服务").toString();
    }

    private void finishUnavailableOperation() {
        boolean wasStopping = pendingStop;
        if (!wasStopping) {
            preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
        }
        clearPendingActions();
        lifecycleState = LifecycleState.ERROR;
        showStatus((wasStopping ? "停止未完成：" : "操作失败：") + noBackendStatus());
    }

    private void finishAmbiguousShizukuOperation() {
        boolean wasStopping = pendingStop;
        boolean existingOwner = activeBackendFromPreferences() == BackendId.SHIZUKU;
        if (!wasStopping && !existingOwner) {
            preferences.edit()
                    .putBoolean(PREF_DESIRED_ENABLED, false)
                    .remove(PREF_ACTIVE_BACKEND)
                    .apply();
            operationBackend = null;
        }
        clearPendingActions();
        lifecycleState = LifecycleState.ERROR;
        showStatus("检测到原生兼容服务正在运行，Shizuku Binder 来源无法唯一确认。\n"
                + "为避免把停止请求发给错误的管理器，请先停止兼容服务，再使用 Shizuku 服务。"
                + (existingOwner ? "\n已保留原 Shizuku 服务归属记录。" : ""));
    }

    private void migrateLegacyBackendState() {
        if (preferences.contains(PREF_BACKEND)) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putString(PREF_BACKEND, Preference.AUTO.storageValue());
        if (preferences.contains(PREF_VERIFICATION_TOKEN)
                && preferences.getBoolean(PREF_DESIRED_ENABLED, false)) {
            editor.putString(PREF_ACTIVE_BACKEND, BackendId.STELLAR.storageValue());
        }
        editor.apply();
    }

    private Preference selectedBackendPreference() {
        if (backendSpinner != null) {
            Object selected = backendSpinner.getSelectedItem();
            if (selected instanceof Preference) return (Preference) selected;
        }
        return savedBackendPreference();
    }

    private Preference savedBackendPreference() {
        return Preference.fromStorage(preferences.getString(PREF_BACKEND, null));
    }

    private BackendId activeBackendFromPreferences() {
        return BackendId.fromStorage(preferences.getString(PREF_ACTIVE_BACKEND, null));
    }

    private void persistSelectedBackendPreferenceIfIdle() {
        if (backendSpinner == null || activeBackendFromPreferences() != null
                || remoteService != null || connectionPending
                || lifecycleState == LifecycleState.STARTING
                || lifecycleState == LifecycleState.STOPPING) return;
        Preference selected = selectedBackendPreference();
        preferences.edit().putString(PREF_BACKEND, selected.storageValue()).apply();
    }

    private static int preferenceIndex(Preference preference) {
        Preference[] values = Preference.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == preference) return i;
        }
        return 0;
    }

    private String getOrCreateVerificationToken() {
        String token = preferences.getString(PREF_VERIFICATION_TOKEN, null);
        if (token != null && !token.isEmpty()) return token;
        token = UUID.randomUUID().toString();
        preferences.edit().putString(PREF_VERIFICATION_TOKEN, token).commit();
        return token;
    }

    private void finishOperationFailure(String message) {
        boolean wasStopping = pendingStop || lifecycleState == LifecycleState.STOPPING;
        if (!wasStopping) preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
        clearPendingActions();
        lifecycleState = LifecycleState.ERROR;
        setButtonsEnabled(true);
        showStatus((wasStopping ? "停止未完成：" : "操作失败：") + message);
        shutdownExecutorIfDestroyed();
    }

    private void clearPendingActions() {
        pendingEnablePackage = null;
        pendingStop = false;
        pendingPermissionRequestCode = -1;
        connectionPending = false;
        connectionToken++;
        setButtonsEnabled(true);
    }

    private void shutdownExecutorIfDestroyed() {
        if (activityDestroyed) ioExecutor.shutdownNow();
    }

    private void claimOperation() {
        operationEpoch = GLOBAL_OPERATION_EPOCH.incrementAndGet();
    }

    private boolean ownsOperation(int epoch) {
        return epoch != 0
                && epoch == operationEpoch
                && epoch == GLOBAL_OPERATION_EPOCH.get();
    }

    private void abandonOperation() {
        int epoch = operationEpoch;
        if (epoch != 0) {
            GLOBAL_OPERATION_EPOCH.compareAndSet(epoch, epoch + 1);
        }
        pendingPermissionRequestCode = -1;
    }

    private boolean beginGlobalStop(int epoch) {
        if (!ownsOperation(epoch)) return false;
        int owner = GLOBAL_STOP_EPOCH.get();
        return owner == epoch || (owner == 0 && GLOBAL_STOP_EPOCH.compareAndSet(0, epoch));
    }

    private static void endGlobalStop(int epoch) {
        GLOBAL_STOP_EPOCH.compareAndSet(epoch, 0);
    }

    private boolean isAnotherStopWorkerRunning() {
        int owner = GLOBAL_STOP_EPOCH.get();
        return owner != 0 && owner != operationEpoch;
    }

    private void submitIo(Runnable action) {
        try {
            ioExecutor.execute(action);
        } catch (RejectedExecutionException error) {
            stopWorkerRunning = false;
            endGlobalStop(operationEpoch);
            if (!activityDestroyed) {
                finishOperationFailure("后台执行器已关闭，请重新打开应用后重试");
            }
        }
    }

    private void showStatus(String message) {
        if (activityDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusText.setText(message);
        } else {
            mainHandler.post(() -> {
                if (!activityDestroyed) statusText.setText(message);
            });
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        if (activityDestroyed || enableButton == null || disableButton == null) return;
        enableButton.setEnabled(enabled && browserPicker != null && browserPicker.getSelection() != null);
        if (browserPicker != null) browserPicker.setEnabled(enabled);
        disableButton.setEnabled(enabled);
        if (backendSpinner != null) {
            backendSpinner.setEnabled(enabled
                    && activeBackendFromPreferences() == null
                    && remoteService == null
                    && !preferences.getBoolean(PREF_DESIRED_ENABLED, false));
        }
    }

    private static String compactError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private interface StopCompletion {
        void complete(UserServiceStopper.Result result);
    }
}
