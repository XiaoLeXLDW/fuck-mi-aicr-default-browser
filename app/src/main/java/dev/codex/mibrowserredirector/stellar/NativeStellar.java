/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package dev.codex.mibrowserredirector.stellar;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.stellar.server.IStellarApplication;
import com.stellar.server.IStellarService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NativeStellar {
    private static final String PERMISSION_STELLAR = "stellar";
    private static final int CLIENT_API_VERSION = 103;

    private static final String ATTACH_API_VERSION = "stellar:attach-api-version";
    private static final String ATTACH_PACKAGE_NAME = "stellar:attach-package-name";
    private static final String REPLY_UID = "stellar:attach-reply-uid";
    private static final String REPLY_VERSION = "stellar:attach-reply-version";
    private static final String REPLY_PERMISSION = "stellar:attach-reply-permission-granted";
    private static final String REPLY_RATIONALE =
            "stellar:attach-reply-should-show-request-permission-rationale";
    private static final String PERMISSION_REPLY_ALLOWED =
            "stellar:request-permission-reply-allowed";

    public interface BinderReceivedListener {
        void onBinderReceived();
    }

    public interface BinderDeadListener {
        void onBinderDead();
    }

    public interface PermissionResultListener {
        void onPermissionResult(int requestCode, boolean allowed);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final List<BinderReceivedListener> RECEIVED_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final List<BinderDeadListener> DEAD_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final List<PermissionResultListener> PERMISSION_LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile IBinder binder;
    private static volatile IStellarService service;
    private static volatile String packageName;
    private static volatile boolean binderReady;
    private static volatile boolean permissionGranted;
    private static volatile boolean shouldShowRationale;
    private static volatile int serverUid = -1;
    private static volatile int serverVersion = -1;

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = NativeStellar::clearBinder;

    private static final IStellarApplication APPLICATION_CALLBACK =
            new IStellarApplication.Stub() {
                @Override
                public void bindApplication(Bundle data) {
                    serverUid = data.getInt(REPLY_UID, -1);
                    serverVersion = data.getInt(REPLY_VERSION, -1);
                    permissionGranted = data.getBoolean(REPLY_PERMISSION, false);
                    shouldShowRationale = data.getBoolean(REPLY_RATIONALE, false);
                    binderReady = true;
                    for (BinderReceivedListener listener : RECEIVED_LISTENERS) {
                        MAIN_HANDLER.post(listener::onBinderReceived);
                    }
                }

                @Override
                public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
                    boolean allowed = data.getBoolean(PERMISSION_REPLY_ALLOWED, false);
                    permissionGranted = allowed;
                    if (allowed) shouldShowRationale = false;
                    for (PermissionResultListener listener : PERMISSION_LISTENERS) {
                        MAIN_HANDLER.post(() -> listener.onPermissionResult(requestCode, allowed));
                    }
                }

                @Override
                public void onServiceStarted() {
                    // No automatic action: desired state is controlled by MainActivity.
                }
            };

    private NativeStellar() {
    }

    public static synchronized void acceptBinder(IBinder newBinder, String clientPackageName) {
        if (newBinder == null || !newBinder.pingBinder()) return;
        if (binder == newBinder && binderReady) return;

        IBinder oldBinder = binder;
        if (oldBinder != null) {
            try {
                oldBinder.unlinkToDeath(DEATH_RECIPIENT, 0);
            } catch (RuntimeException ignored) {
                // The old server is already dead.
            }
        }

        binder = newBinder;
        service = IStellarService.Stub.asInterface(newBinder);
        packageName = clientPackageName;
        binderReady = false;
        try {
            newBinder.linkToDeath(DEATH_RECIPIENT, 0);
            Bundle args = new Bundle();
            args.putInt(ATTACH_API_VERSION, CLIENT_API_VERSION);
            args.putString(ATTACH_PACKAGE_NAME, clientPackageName);
            service.attachApplication(APPLICATION_CALLBACK, args);
        } catch (Throwable e) {
            clearBinder();
        }
    }

    public static synchronized void clearBinder() {
        IBinder oldBinder = binder;
        binder = null;
        service = null;
        packageName = null;
        binderReady = false;
        permissionGranted = false;
        shouldShowRationale = false;
        serverUid = -1;
        serverVersion = -1;
        if (oldBinder != null) {
            try {
                oldBinder.unlinkToDeath(DEATH_RECIPIENT, 0);
            } catch (RuntimeException ignored) {
                // Binder death already removed the recipient.
            }
        }
        for (BinderDeadListener listener : DEAD_LISTENERS) {
            MAIN_HANDLER.post(listener::onBinderDead);
        }
    }

    public static boolean pingBinder() {
        IBinder current = binder;
        return binderReady && current != null && current.pingBinder();
    }

    public static boolean checkSelfPermission() {
        IStellarService current = service;
        if (current == null) return false;
        try {
            permissionGranted = current.checkSelfPermission(PERMISSION_STELLAR);
            return permissionGranted;
        } catch (RemoteException e) {
            return false;
        }
    }

    public static boolean shouldShowRequestPermissionRationale() {
        if (permissionGranted) return false;
        IStellarService current = service;
        if (current == null) return shouldShowRationale;
        try {
            shouldShowRationale = current.shouldShowRequestPermissionRationale();
        } catch (RemoteException ignored) {
            // Keep the latest server-provided value.
        }
        return shouldShowRationale;
    }

    public static void requestPermission(int requestCode) throws RemoteException {
        requireService().requestPermission(PERMISSION_STELLAR, requestCode);
    }

    public static int getServerUid() {
        return serverUid;
    }

    public static int getServerVersion() {
        return serverVersion;
    }

    public static IStellarService requireService() {
        IStellarService current = service;
        if (current == null) throw new IllegalStateException("Stellar service is not connected");
        return current;
    }

    public static String getPackageName() {
        return packageName;
    }

    public static IBinder getBinder() {
        return binder;
    }

    public static IBinder getClientBinder() {
        return APPLICATION_CALLBACK.asBinder();
    }

    public static void addBinderReceivedListenerSticky(BinderReceivedListener listener) {
        RECEIVED_LISTENERS.add(listener);
        if (pingBinder()) MAIN_HANDLER.post(listener::onBinderReceived);
    }

    public static void removeBinderReceivedListener(BinderReceivedListener listener) {
        RECEIVED_LISTENERS.remove(listener);
    }

    public static void addBinderDeadListener(BinderDeadListener listener) {
        DEAD_LISTENERS.add(listener);
    }

    public static void removeBinderDeadListener(BinderDeadListener listener) {
        DEAD_LISTENERS.remove(listener);
    }

    public static void addPermissionResultListener(PermissionResultListener listener) {
        PERMISSION_LISTENERS.add(listener);
    }

    public static void removePermissionResultListener(PermissionResultListener listener) {
        PERMISSION_LISTENERS.remove(listener);
    }
}
