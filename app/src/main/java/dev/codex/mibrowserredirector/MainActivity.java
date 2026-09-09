package dev.codex.mibrowserredirector;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.View;
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
    private static final ServiceTaskRunner SERVICE_TASKS = new ServiceTaskRunner();
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
    private final List<BrowserOption> browsers = new ArrayList<>();

    private Spinner backendSpinner;
    private BrowserPicker browserPicker;
    private TextView statusText;
    private TextView diagnosticsText;
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
    private volatile boolean connectionPending;
    private volatile int connectionToken;
    // Unlike connectionToken, this survives timeout/cancel so a late first Binder
    // can still be retained for cleanup. Starting a newer bind supersedes it.
    private int latestBindingToken;
    private boolean identityRetryAttempted;
    private volatile boolean activityDestroyed;
    private String pendingEnablePackage;
    private boolean pendingEnableObserveOnly;
    private boolean pendingStop;
    private boolean stopWorkerRunning;
    private volatile int operationEpoch;
    private volatile int rpcToken;
    private boolean stateReadPending;
    private volatile boolean managerPending;
    private volatile int managerToken;
    private long stopDeadlineNanos;
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
                            if (interruptedSession != null) detachSession(interruptedSession);
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
                    if (stopWorkerRunning) return; // The stop worker owns this cleanup identity.
                    if (!session.allowsServiceCommands(binder)) {
                        onCleanupRequired(session, binder, "服务身份未通过，等待确认清理");
                        return;
                    }
                    stateReadPending = false;
                    rpcToken++;
                    connectionPending = false;
                    connectionToken++;
                    operationBackend = session.backendId();
                    // Preserve the raw Binder before protocol RPCs, so a stalled verification
                    // never prevents the independent stop lane from reaching this process.
                    remoteBinder = binder;
                    remoteService = IRedirectorService.Stub.asInterface(binder);
                    if (pendingStop || !preferences.getBoolean(PREF_DESIRED_ENABLED, false)) {
                        if (!ownsOperation(operationEpoch)) claimOperation();
                        pendingStop = true;
                        stopConnectedService(remoteService, binder);
                    } else verifyConnectedService(session, binder);
                }

                @Override
                public void onServiceDisconnected(ServiceSession session) {
                    if (serviceSession != session) return;
                    if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
                    stateReadPending = false;
                    rpcToken++;
                    if (stopWorkerRunning) return; // A disconnect callback is not death confirmation.
                    if (lifecycleState == LifecycleState.STOPPING && pendingStop
                            && !stopWorkerRunning
                            && serviceSession != null) {
                        connectionPending = false;
                        connectionToken++;
                        stopConnectedService(remoteService, remoteBinder);
                        return;
                    }
                    detachSession(session);
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
                    stateReadPending = false;
                    rpcToken++;
                    detachSession(session);
                    connectionPending = false;
                    connectionToken++;
                    serviceSession = null;
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
                }

                @Override
                public void onCleanupRequired(ServiceSession session, IBinder binder, String message) {
                    if (serviceSession != session || activityDestroyed || stopWorkerRunning) return;
                    if (!ownsOperation(operationEpoch)) claimOperation();
                    preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false)
                            .putString(PREF_ACTIVE_BACKEND, session.backendId().storageValue()).apply();
                    clearPendingActions();
                    operationBackend = session.backendId();
                    remoteBinder = binder;
                    remoteService = null; // An identity-rejected Binder is not a business endpoint.
                    pendingStop = true;
                    if (stopDeadlineNanos == 0L) armWholeStopDeadline();
                    eventText.setText(message == null ? "身份异常，等待确认清理" : message);
                    stopConnectedService(null, binder);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Android 8.0 has the navigation icon flag; its theme attribute arrived in 8.1.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            boolean night = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            decor.setSystemUiVisibility(night
                    ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        PageInsets.install(findViewById(R.id.page_scroll),
                getResources().getDimensionPixelSize(R.dimen.page_top_safe_margin));

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateLegacyBackendState();
        privilegeRuntime = PrivilegeRuntime.create(
                this, mainHandler, getOrCreateVerificationToken());

        backendSpinner = findViewById(R.id.backend_spinner);
        statusText = findViewById(R.id.status_text);
        diagnosticsText = findViewById(R.id.diagnostics_text);
        Button diagnosticsToggle = findViewById(R.id.diagnostics_toggle);
        diagnosticsToggle.setOnClickListener(view -> {
            boolean expand = diagnosticsText.getVisibility() != android.view.View.VISIBLE;
            diagnosticsText.setVisibility(expand ? android.view.View.VISIBLE : android.view.View.GONE);
            diagnosticsToggle.setText(expand ? R.string.diagnostics_collapse : R.string.diagnostics_expand);
        });
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
            if (session != null) detachSession(session);
            serviceSession = null;
            remoteService = null;
            remoteBinder = null;
        }
        super.onDestroy();
    }

    private void loadBrowsers() {
        PackageManager pm = getPackageManager();
        Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> handlers = BrowserDiscovery.queryHandlers(pm, "https");
        Set<String> genericHttps = BrowserDiscovery.genericPackages(handlers, "https");
        Set<String> genericHttp = BrowserDiscovery.genericPackages(
                BrowserDiscovery.queryHandlers(pm, "http"), "http");
        Set<String> seen = new HashSet<>();
        List<BrowserOption> found = new ArrayList<>();
        for (ResolveInfo info : handlers) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (!BrowserEligibility.accepts(packageName, getPackageName(),
                    genericHttp.contains(packageName), genericHttps.contains(packageName))) {
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
            eventText.setText("未发现同时支持通用 HTTP 和 HTTPS 网页的可用浏览器。请安装小米浏览器以外的浏览器，再点“刷新状态”。");
        }
    }

    private void requestEnable() {
        if (UserServiceStopper.hasPendingCalls()
                || (!preferences.getBoolean(PREF_DESIRED_ENABLED, false)
                && activeBackendFromPreferences() != null)) {
            showStatus("上次服务清理尚未确认，请先点“停用并退出服务”；不能在旧调用未结束时重新开启");
            return;
        }
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
        if (UserServiceStopper.hasPendingCalls()) {
            showStatus("停止未确认：上一次系统调用仍未返回，暂不能重新开启。稍后点“刷新状态”；如持续无响应，请在对应管理器中停止权限服务。");
            return;
        }
        if (isAnotherStopWorkerRunning()) {
            showStatus("停服任务已在另一个页面实例中运行，请等待最多 4 秒");
            return;
        }
        if (lifecycleState == LifecycleState.STOPPING || stopWorkerRunning) {
            showStatus("停止流程已在运行，请等待最多 12 秒");
            return;
        }
        preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).commit();
        claimOperation();
        connectionPending = false;
        connectionToken++;
        pendingStop = true;
        pendingEnablePackage = null;
        identityRetryAttempted = false;
        operationBackend = serviceSession == null
                ? activeBackendFromPreferences()
                : serviceSession.backendId();
        lifecycleState = LifecycleState.STOPPING;
        setButtonsEnabled(false);
        showStatus("正在停止服务…", "停止第 1/3 步：正在连接原后端的服务身份…");
        armWholeStopDeadline();
        runPendingAction();
    }

    private void armWholeStopDeadline() {
        int epoch = operationEpoch;
        stopDeadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        mainHandler.postDelayed(() -> {
            if (!ownsOperation(epoch) || activityDestroyed
                    || (!pendingStop && lifecycleState != LifecycleState.STOPPING)) return;
            if (stopWorkerRunning) {
                showStatus("停止未确认：已等待 12 秒，清理尚未结束。暂不能重新开启；稍后点“刷新状态”查看进展。");
            } else {
                finishOperationFailure("总等待时间已到（12 秒），保留原后端，稍后可再次停用");
            }
        }, 12_000L);
    }

    private void runPendingAction() {
        if (pendingEnablePackage == null && !pendingStop) return;
        if (pendingStop && serviceSession != null) {
            stopConnectedService(remoteService, remoteBinder);
            return;
        }
        queryBackend(true);
    }

    private void queryBackend(boolean forOperation) {
        if (managerPending || activityDestroyed) return;
        managerPending = true;
        int token = ++managerToken;
        int revision = GLOBAL_OPERATION_EPOCH.get();
        BackendId pinned = activeBackendFromPreferences();
        BackendId fixed = forOperation ? operationBackend : null;
        Preference preference = selectedBackendPreference();
        if (forOperation && !pendingStop) lifecycleState = LifecycleState.STARTING;
        if (forOperation) setButtonsEnabled(false);
        watchManager(token, revision, forOperation, "权限管理器查询超时（8 秒）");
        try {
            SERVICE_TASKS.executeIo(() -> {
                if (!acceptManagerResult(token, revision)) return;
                BackendSnapshot backend = fixed == null
                        ? privilegeRuntime.choose(preference, pinned) : privilegeRuntime.snapshot(fixed);
                boolean ambiguous = forOperation && backend != null
                        && privilegeRuntime.hasAmbiguousShizukuSource(backend.id());
                mainHandler.post(() -> {
                    if (!acceptManagerResult(token, revision)) return;
                    managerPending = false;
                    managerToken++;
                    if (forOperation && (stopWorkerRunning
                            || (pendingEnablePackage == null && !pendingStop))) return;
                    if (!forOperation) {
                        refreshWithBackend(pinned, backend);
                    } else if (backend == null) {
                        finishUnavailableOperation();
                    } else if (ambiguous) {
                        finishAmbiguousShizukuOperation();
                    } else {
                        operationBackend = backend.id();
                        if (!ensurePrivilegePermission(backend)) return;
                        if (remoteService == null || remoteBinder == null) bindForPendingAction();
                        else if (pendingStop) stopConnectedService(remoteService, remoteBinder);
                        else executeEnable();
                    }
                });
            });
        } catch (RejectedExecutionException busy) {
            managerPending = false;
            managerToken++;
            if (forOperation) finishOperationFailure("权限调用队列繁忙，请稍后重试；已连接服务仍可直接停用");
            else showStatus("权限状态读取队列繁忙，可稍后刷新");
        }
    }

    private boolean acceptManagerResult(int token, int revision) {
        return !activityDestroyed && managerPending && managerToken == token
                && GLOBAL_OPERATION_EPOCH.get() == revision;
    }

    private void watchManager(int token, int revision, boolean forOperation, String message) {
        mainHandler.postDelayed(() -> {
            if (!acceptManagerResult(token, revision)) return;
            managerPending = false;
            managerToken++;
            if (forOperation) finishOperationFailure(message);
            else showStatus(message + "；界面仍可操作");
        }, CONNECTION_TIMEOUT_MILLIS);
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
        latestBindingToken = token;
        if (!pendingStop) lifecycleState = LifecycleState.STARTING;
        preferences.edit().putString(PREF_ACTIVE_BACKEND, backendId.storageValue()).apply();
        setButtonsEnabled(false);
        showStatus(pendingStop
                ? "停止第 1/3 步：正在通过 " + backendId.displayName() + " 取得服务身份…"
                : "正在通过 " + backendId.displayName() + " 启动 " + ServiceIdentity.BUILD_LABEL + "…");

        // Register BEFORE submitting the synchronous manager call; its return is not required.
        mainHandler.postDelayed(() -> {
            if (!ownsOperation(epoch) || !connectionPending || token != connectionToken) return;
            boolean wasStopping = pendingStop;
            preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
            clearPendingActions();
            lifecycleState = LifecycleState.ERROR;
            setButtonsEnabled(true);
            showStatus(wasStopping ? "停止未确认：8 秒内没有取得 UserService Binder"
                    : "UserService 连接超时（8 秒）；保留原后端，请点停用重试清理");
        }, CONNECTION_TIMEOUT_MILLIS);

        PrivilegeRuntime.ServiceCallback callback = callbackForBinding(epoch, token);
        try {
            SERVICE_TASKS.executeIo(() -> {
                if (activityDestroyed || !ownsOperation(epoch)
                        || !connectionPending || token != connectionToken) return;
                try {
                    ServiceSession bound = privilegeRuntime.bind(backendId, callback);
                    mainHandler.post(() -> adoptBinding(epoch, token, bound));
                } catch (RuntimeException error) {
                    mainHandler.post(() -> {
                        if (!ownsOperation(epoch) || !connectionPending || token != connectionToken) return;
                        finishOperationFailure("UserService 绑定失败：" + compactError(error));
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            finishOperationFailure("绑定队列繁忙；保留原后端，稍后可停用清理");
        }
    }

    // A Binder callback can arrive BEFORE bind() returns. Adopt its exact session first;
    // the later return must not detach that same, already-adopted session.
    private boolean adoptBinding(int epoch, int token, ServiceSession session) {
        // An established session survives user actions (new epochs); its object identity
        // still fences out callbacks from any replaced binding.
        if (!activityDestroyed && serviceSession == session) return true;
        if (!activityDestroyed && ownsOperation(epoch) && connectionPending && token == connectionToken) {
            ServiceSession previous = serviceSession;
            stateReadPending = false;
            rpcToken++;
            serviceSession = session;
            if (previous != null && previous != session) detachSession(previous);
            return true;
        }
        // bind() may still be in flight when its watchdog expires or Stop starts a
        // new epoch. Keep that attempt's first Binder only for cleanup, never for
        // enable and never in place of a newer binding or another page's session.
        if (!activityDestroyed && ownsOperation(operationEpoch)
                && !preferences.getBoolean(PREF_DESIRED_ENABLED, false)
                && !stopWorkerRunning && serviceSession == null
                && token == latestBindingToken
                && session.backendId() == activeBackendFromPreferences()) {
            serviceSession = session;
            return true;
        }
        // An established session can still belong to an independent stop worker after a
        // new stop epoch/page is created. Do not detach its in-flight callback identity.
        if (serviceSession != session) detachSession(session);
        return false;
    }

    private PrivilegeRuntime.ServiceCallback callbackForBinding(int epoch, int token) {
        return new PrivilegeRuntime.ServiceCallback() {
            @Override public void onCleanupRequired(ServiceSession session, IBinder binder, String message) {
                mainHandler.post(() -> {
                    if (adoptBinding(epoch, token, session)) userServiceCallback.onCleanupRequired(session, binder, message);
                });
            }
            @Override public void onServiceConnected(ServiceSession session, IBinder binder) {
                mainHandler.post(() -> {
                    if (adoptBinding(epoch, token, session)) userServiceCallback.onServiceConnected(session, binder);
                });
            }
            @Override public void onServiceDisconnected(ServiceSession session) {
                mainHandler.post(() -> {
                    if (adoptBinding(epoch, token, session)) userServiceCallback.onServiceDisconnected(session);
                });
            }
            @Override public void onServiceStartFailed(ServiceSession session, int code, String message) {
                mainHandler.post(() -> {
                    if (ownsOperation(epoch) && connectionPending && token == connectionToken
                            && adoptBinding(epoch, token, session)) {
                        userServiceCallback.onServiceStartFailed(session, code, message);
                    }
                });
            }
        };
    }

    private void detachSession(ServiceSession session) {
        if (session == null) return;
        try {
            SERVICE_TASKS.executeIo(() -> {
                try { session.detach(); } catch (RuntimeException ignored) { }
            });
        } catch (RejectedExecutionException ignored) {
            // Callback detachment is best effort; it is never proof of process death.
        }
    }

    private void verifyConnectedService(ServiceSession session, IBinder binder) {
        // asInterface() can return a new Proxy each time for the same Binder.
        // Keep the captured interface identity that the RPC watchdog fences on.
        IRedirectorService candidate = remoteService;
        if (candidate == null || binder != remoteBinder || session != serviceSession) return;
        int epoch = operationEpoch;
        int verificationToken = ++rpcToken;
        watchRpc(epoch, verificationToken, candidate, binder, session, true, "服务身份验证超时（8 秒）");
        submitIo(() -> {
            if (rpcToken != verificationToken) return;
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
            mainHandler.post(() -> {
                if (rpcToken != verificationToken || !ownsOperation(epoch)) return;
                rpcToken++;
                finishServiceVerification(
                    epoch,
                    session,
                    candidate,
                    binder,
                    detectedProtocol,
                    detectedGeneration,
                    detectedFailure
                );
            });
        });
    }

    private void finishServiceVerification(int epoch, ServiceSession session,
            IRedirectorService candidate, IBinder binder, int protocol,
            int serviceGeneration, String failure) {
        if (!ownsOperation(epoch) || serviceSession != session) return;
        if (activityDestroyed && lifecycleState != LifecycleState.STOPPING) return;
        boolean matches = failure == null && protocol == ServiceIdentity.PROTOCOL_VERSION
                && serviceGeneration == ServiceIdentity.USER_SERVICE_GENERATION;
        if (!matches) {
            session.rejectServiceCommands(binder);
            if (!beginGlobalStop(epoch)) {
                finishOperationFailure("服务清理正在进行，请稍后重试");
                return;
            }
            stopWorkerRunning = true;
            remoteService = null;
            remoteBinder = null;
            lifecycleState = LifecycleState.STOPPING;
            setButtonsEnabled(false);
            String reason = failure == null
                    ? "服务版本不兼容（协议/服务代=" + protocol + "/" + serviceGeneration + "）"
                    : "服务身份读取失败：" + failure;
            showStatus(reason + "\n已拒绝使用该 UserService，正在通过来源管理器请求清理…");
            stopSpecificService(candidate, binder, session, result -> {
                stopWorkerRunning = false;
                endGlobalStop(epoch);
                if (!ownsOperation(epoch)) return;
                if (!result.stopped) {
                    preferences.edit().putBoolean(PREF_DESIRED_ENABLED, false).apply();
                    restoreFailedStop(candidate, binder, session);
                    finishOperationFailure("UserService 清理未确认\n" + result.detail);
                } else if (!identityRetryAttempted) {
                    detachSession(session);
                    if (serviceSession == session) serviceSession = null;
                    identityRetryAttempted = true;
                    lifecycleState = LifecycleState.STARTING;
                    mainHandler.postDelayed(this::bindForPendingAction, 250L);
                } else {
                    finishOperationFailure("连续两次未通过 UserService 身份验证，已停止重试");
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
        int enableToken = ++rpcToken;
        pendingEnablePackage = null;
        lifecycleState = LifecycleState.STARTING;
        setButtonsEnabled(false);
        showStatus("正在开启…", "正在启用 " + ServiceIdentity.BUILD_LABEL + " 控制器…");
        watchRpc(epoch, enableToken, service, binder, session, false, "启用调用超时（8 秒）");
        submitIo(() -> {
            if (rpcToken != enableToken) return;
            try {
                String result = service.enable(target, observeOnly);
                boolean enabled = service.isEnabled();
                mainHandler.post(() -> {
                    if (!ownsOperation(epoch) || service != remoteService
                            || session != serviceSession || activityDestroyed || rpcToken != enableToken) return;
                    rpcToken++;
                    if (!enabled) {
                        cleanupFailedEnable(epoch, service, binder, session,
                                result == null || result.isEmpty()
                                        ? "UserService 未进入运行状态"
                                        : result);
                        return;
                    }
                    lifecycleState = LifecycleState.RUNNING;
                    showStatus(observeOnly ? "观察模式已开启" : "接管模式已开启", result);
                    setButtonsEnabled(true);
                    refreshRemoteState();
                });
            } catch (RemoteException | RuntimeException e) {
                mainHandler.post(() -> {
                    if (!ownsOperation(epoch) || service != remoteService
                            || session != serviceSession || activityDestroyed || rpcToken != enableToken) return;
                    rpcToken++;
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
                return;
            }
            if (!result.stopped) {
                restoreFailedStop(service, binder, session);
                lifecycleState = LifecycleState.ERROR;
                setButtonsEnabled(true);
                showStatus("开启失败，且清理未确认\n" + failure + "\n" + result.detail);
                endGlobalStop(epoch);
                return;
            }
            if (serviceSession == session) serviceSession = null;
            detachSession(session);
            operationBackend = null;
            preferences.edit().remove(PREF_ACTIVE_BACKEND).apply();
            lifecycleState = LifecycleState.ERROR;
            setButtonsEnabled(true);
            showStatus("开启失败；残留 UserService 已清理\n" + failure);
            endGlobalStop(epoch);
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
        managerPending = false;
        managerToken++;
        rpcToken++;
        pendingPermissionRequestCode = -1;
        stopWorkerRunning = true;
        remoteService = null;
        remoteBinder = null;
        connectionPending = false;
        connectionToken++;
        lifecycleState = LifecycleState.STOPPING;
        setButtonsEnabled(false);
        showStatus("正在停止服务，等待确认退出…", "停止第 2/3 步：正在请求停止服务并确认退出…");
        stopSpecificService(service, binder, stopSession, result -> {
            stopWorkerRunning = false;
            if (!ownsOperation(epoch)) {
                endGlobalStop(epoch);
                return;
            }
            if (!result.stopped) {
                restoreFailedStop(service, binder, stopSession);
                finishOperationFailure(result.detail);
                endGlobalStop(epoch);
                return;
            }
            if (serviceSession == stopSession) serviceSession = null;
            if (stopSession != null) detachSession(stopSession);
            pendingStop = false;
            pendingEnablePackage = null;
            operationBackend = null;
            preferences.edit().remove(PREF_ACTIVE_BACKEND).apply();
            lifecycleState = LifecycleState.STOPPED;
            setButtonsEnabled(true);
            String message = "停止完成 [" + ServiceIdentity.BUILD_LABEL + "]\n"
                    + "第 3/3 步：已确认 UserService Binder 死亡，不会自动重连";
            showStatus("停止完成：服务已退出，不会自动重连", message);
            if (!activityDestroyed) eventText.setText("已停用接管");
            endGlobalStop(epoch);
        });
    }

    private void stopSpecificService(IRedirectorService service, IBinder binder,
            ServiceSession stopSession, StopCompletion completion) {
        long deadline = pendingStop ? stopDeadlineNanos : 0L;
        try {
            SERVICE_TASKS.executeStop(() -> {
                long budgetMillis = deadline == 0L ? 4000L : Math.min(4000L,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                UserServiceStopper.Result result = budgetMillis <= 0L
                        ? new UserServiceStopper.Result(false, "总停止截止时间已到，未发起新的清理调用")
                        : performStop(service, binder, stopSession, budgetMillis);
                if (result.stopped && stopSession != null) stopSession.onStopConfirmed();
                mainHandler.post(() -> completion.complete(result));
            });
        } catch (RejectedExecutionException busy) {
            mainHandler.post(() -> completion.complete(new UserServiceStopper.Result(false,
                    "停止执行器繁忙；没有确认退出，请稍后重试")));
        }
    }

    private UserServiceStopper.Result performStop(IRedirectorService service, IBinder binder,
            ServiceSession stopSession, long budgetMillis) {
        try {
            return UserServiceStopper.stop(
                    new UserServiceStopper.Endpoint() {
                        @Override
                        public String disable() throws Exception {
                            if (stopSession != null && !stopSession.allowsServiceCommands(binder)) {
                                return "身份未通过：仅向来源管理器请求停止";
                            }
                            IRedirectorService endpoint = service;
                            if (endpoint == null && stopSession != null) {
                                endpoint = IRedirectorService.Stub.asInterface(stopSession.cleanupBinder());
                            }
                            return endpoint == null ? "尚无可信业务 Binder" : endpoint.disable();
                        }

                        @Override
                        public void destroy() throws Exception {
                            if (stopSession == null ? service != null : stopSession.allowsServiceCommands(binder)) {
                                IBinder target = stopSession == null ? null : stopSession.cleanupBinder();
                                if (target == null) target = binder;
                                if (target != null) UserServiceBinderHelper.destroy(target);
                            }
                        }

                        @Override
                        public boolean isAlive() {
                            IBinder observed = stopSession == null ? null : stopSession.cleanupBinder();
                            if (observed == null) observed = binder;
                            // No Binder is missing evidence, not proof that a process is dead.
                            return observed == null || UserServiceBinderHelper.isAlive(observed);
                        }
                    },
                    () -> {
                        if (stopSession != null) stopSession.remove();
                    }, budgetMillis
            );
        } catch (Throwable error) {
            return new UserServiceStopper.Result(false,
                    "停止流程异常：" + compactError(error));
        }
    }

    private void restoreFailedStop(IRedirectorService service, IBinder binder,
            ServiceSession stopSession) {
        // Do not perform pingBinder on the UI thread, especially after a timed-out stop.
        remoteService = service;
        remoteBinder = binder;
        serviceSession = stopSession;
        if (stopSession != null) operationBackend = stopSession.backendId();
    }



    private boolean ensurePrivilegePermission(BackendSnapshot initialSnapshot) {
        BackendSnapshot backend = initialSnapshot;
        if (!backend.available()) {
            finishOperationFailure(backend.id().displayName() + (pendingStop
                    ? " 未运行；已记住“停止”状态，但目前无法核验残留进程"
                    : " 未运行。请打开对应管理器，按其启动指引启动服务，再回到本页点“开启接管”。"));
            return false;
        }
        if (backend.authorized()) return true;
        if (backend.permissionRationale()) {
            finishOperationFailure(backend.id().displayName()
                    + " 权限已被拒绝，请到管理器的授权应用列表中重新允许");
            return false;
        }
        showStatus("正在请求 " + backend.id().displayName() + " 授权…");
        pendingPermissionRequestCode = PRIVILEGE_PERMISSION_REQUEST + (operationEpoch & 0x0000ffff);
        int requestCode = pendingPermissionRequestCode;
        int revision = GLOBAL_OPERATION_EPOCH.get();
        int token = ++managerToken;
        managerPending = true;
        watchManager(token, revision, true, "授权请求发送超时（8 秒）");
        try {
            SERVICE_TASKS.executeIo(() -> {
                if (!acceptManagerResult(token, revision)) return;
                try {
                    privilegeRuntime.requestPermission(backend.id(), requestCode);
                    mainHandler.post(() -> {
                        if (!acceptManagerResult(token, revision)) return;
                        managerPending = false;
                        managerToken++;
                        // Authorization itself may await user input without an artificial timer.
                        if (pendingPermissionRequestCode == -1) runPendingAction();
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        if (!acceptManagerResult(token, revision)) return;
                        finishOperationFailure(backend.id().displayName() + " 授权请求失败：" + compactError(error));
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            finishOperationFailure("授权请求队列繁忙，请稍后重试");
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
        if (UserServiceStopper.hasPendingCalls() && !stopWorkerRunning && GLOBAL_STOP_EPOCH.get() == 0) {
            lifecycleState = LifecycleState.ERROR;
            showStatus("停止未确认：系统调用仍未返回，暂不能重新开启。稍后点“刷新状态”；如持续无响应，请在对应管理器中停止权限服务。");
            setButtonsEnabled(true);
            return;
        }
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

        queryBackend(false);
    }

    private void refreshWithBackend(BackendId pinned, BackendSnapshot backend) {
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
        if (service == null || session == null || lifecycleState == LifecycleState.STOPPING
                || stateReadPending) return;
        stateReadPending = true;
        int epoch = operationEpoch;
        int readToken = ++rpcToken;
        mainHandler.postDelayed(() -> {
            if (!ownsOperation(epoch) || rpcToken != readToken || !stateReadPending) return;
            stateReadPending = false;
            rpcToken++;
            showStatus("读取状态超时（8 秒）；状态未知，可直接点停用清理，不必等待读取返回");
        }, CONNECTION_TIMEOUT_MILLIS);
        submitIo(() -> {
            if (rpcToken != readToken) return;
            try {
                String state = service.getState();
                String event = service.getLastEvent();
                BackendSnapshot backend = privilegeRuntime.snapshot(session.backendId());
                mainHandler.post(() -> {
                    if (service != remoteService || session != serviceSession
                            || activityDestroyed || !ownsOperation(epoch) || rpcToken != readToken) return;
                    stateReadPending = false;
                    rpcToken++;
                    showStatus("权限服务：" + session.backendId().displayName()
                                    + "\n" + summarizeServiceState(state),
                            "权限服务：" + session.backendId().displayName()
                                    + "（服务 API " + backend.serverVersion() + "）\n" + state);
                    eventText.setText(event);
                });
            } catch (RemoteException | RuntimeException e) {
                mainHandler.post(() -> {
                    if (service != remoteService || session != serviceSession
                            || activityDestroyed || !ownsOperation(epoch) || rpcToken != readToken) return;
                    stateReadPending = false;
                    rpcToken++;
                    lifecycleState = LifecycleState.ERROR;
                    showStatus("读取 UserService 状态失败：" + compactError(e)
                            + "\n仍保留原后端，可直接点“停用并退出服务”重试清理");
                    setButtonsEnabled(true);
                });
            }
        });
    }

    private void showStoppedState(BackendSnapshot backend) {
        showStatus("权限服务：" + backend.id().displayName() + "\n授权：已允许\n状态：已停止",
                "权限服务：" + backend.id().displayName()
                + "（服务 API " + backend.serverVersion() + "）\n"
                + "授权：已允许\n"
                + "控制器 " + ServiceIdentity.BUILD_LABEL + "：已停止\n"
                + "停止状态不会自动创建或重连 UserService");
        if (!activityDestroyed) {
            eventText.setText("选择权限后端和目标浏览器后，点“开启接管”。切换权限后端前，先点“停用并退出服务”，"
                    + "等待“停止完成”。");
        }
        setButtonsEnabled(true);
    }

    private String noBackendStatus() {
        // Manager probing happens only in the bounded background lane.
        return "没有可用的权限后端\n请先启动 Shizuku 或兼容服务，再点刷新状态";
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
    }

    private void clearPendingActions() {
        stateReadPending = false;
        managerPending = false;
        managerToken++;
        rpcToken++;
        pendingEnablePackage = null;
        pendingStop = false;
        pendingPermissionRequestCode = -1;
        connectionPending = false;
        connectionToken++;
        setButtonsEnabled(true);
    }

    private void claimOperation() {
        stopDeadlineNanos = 0L;
        managerPending = false;
        managerToken++;
        rpcToken++;
        stateReadPending = false;
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
        int epoch = operationEpoch;
        try {
            SERVICE_TASKS.executeIo(() -> {
                if (!activityDestroyed && ownsOperation(epoch)) action.run();
            });
        } catch (RejectedExecutionException error) {
            stopWorkerRunning = false;
            endGlobalStop(operationEpoch);
            if (!activityDestroyed) {
                finishOperationFailure("后台调用队列繁忙；停止通道独立，可直接点停用清理");
            }
        }
    }

    private void watchRpc(int epoch, int token, IRedirectorService service, IBinder binder,
            ServiceSession session, boolean verifyingIdentity, String failure) {
        mainHandler.postDelayed(() -> finishRpcTimeout(epoch, token, service, binder,
                session, verifyingIdentity, failure), CONNECTION_TIMEOUT_MILLIS);
    }

    private void finishRpcTimeout(int epoch, int token, IRedirectorService service, IBinder binder,
            ServiceSession session, boolean verifyingIdentity, String failure) {
        if (!ownsOperation(epoch) || activityDestroyed || rpcToken != token
                || service != remoteService || session != serviceSession) return;
        // A valid transport token does not establish that business transaction IDs are safe.
        if (verifyingIdentity) session.rejectServiceCommands(binder);
        rpcToken++;
        pendingEnablePackage = null;
        cleanupFailedEnable(epoch, service, binder, session, failure);
    }

    private void showStatus(String message) {
        showStatus(message, message);
    }

    private void showStatus(String message, String diagnostics) {
        if (activityDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusText.setText(message);
            if (diagnosticsText != null) diagnosticsText.setText(diagnostics);
        } else {
            mainHandler.post(() -> {
                if (!activityDestroyed) {
                    statusText.setText(message);
                    if (diagnosticsText != null) diagnosticsText.setText(diagnostics);
                }
            });
        }
    }

    // Only known metadata is folded; unknown lines and failure messages stay visible.
    static String summarizeServiceState(String state) {
        if (state == null || state.isEmpty()) return "状态未知，请刷新或停用服务";
        StringBuilder summary = new StringBuilder();
        for (String line : state.split("\n")) {
            if (line.startsWith("服务版本：") || line.startsWith("服务代：")
                    || line.startsWith("系统接口：") || line.startsWith("UserService UID/PID：")) continue;
            if (summary.length() > 0) summary.append('\n');
            summary.append(line);
        }
        return summary.length() == 0 ? "状态未知，请展开诊断信息" : summary.toString();
    }

    private void setButtonsEnabled(boolean enabled) {
        if (activityDestroyed || enableButton == null || disableButton == null) return;
        boolean needsCleanup = !preferences.getBoolean(PREF_DESIRED_ENABLED, false)
                && activeBackendFromPreferences() != null;
        enableButton.setEnabled(enabled && !needsCleanup && !UserServiceStopper.hasPendingCalls()
                && browserPicker != null && browserPicker.getSelection() != null);
        if (browserPicker != null) browserPicker.setEnabled(enabled);
        disableButton.setEnabled(enabled || lifecycleState == LifecycleState.STARTING || connectionPending);
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
