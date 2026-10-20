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
import java.util.IdentityHashMap;
import java.util.Map;
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

    // Stellar retains the first client callback for a UID/PID. Keep that identity for the
    // manager Binder's lifetime, including retries after a failed attach or local clear.
    // Only local state is guarded by NativeStellar.class; manager RPCs run outside that lock.
    private static volatile Connection connection;
    private static final Map<IBinder, Connection> CONNECTIONS = new IdentityHashMap<>();
    private static long connectionRevision;
    private static volatile IStellarApplication clientCallback = new IStellarApplication.Stub() {
        @Override public void bindApplication(Bundle data) { }
        @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) { }
        @Override public void onServiceStarted() { }
    };

    private static final class Connection {
        final IBinder binder;
        final IStellarService service;
        final String packageName;
        final IBinder.DeathRecipient deathRecipient = () -> managerDied(this);
        final IStellarApplication callback = new IStellarApplication.Stub() {
            @Override
            public void bindApplication(Bundle data) {
                synchronized (NativeStellar.class) {
                    if (connection != Connection.this) return;
                    serverUid = data.getInt(REPLY_UID, -1);
                    serverVersion = data.getInt(REPLY_VERSION, -1);
                    permissionGranted = data.getBoolean(REPLY_PERMISSION, false);
                    shouldShowRationale = data.getBoolean(REPLY_RATIONALE, false);
                    ready = true;
                }
                for (BinderReceivedListener listener : RECEIVED_LISTENERS) {
                    postIfCurrent(Connection.this, listener::onBinderReceived);
                }
            }

            @Override
            public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
                boolean allowed = data.getBoolean(PERMISSION_REPLY_ALLOWED, false);
                synchronized (NativeStellar.class) {
                    if (connection != Connection.this) return;
                    permissionGranted = allowed;
                    if (allowed) shouldShowRationale = false;
                }
                for (PermissionResultListener listener : PERMISSION_LISTENERS) {
                    postIfCurrent(Connection.this,
                            () -> listener.onPermissionResult(requestCode, allowed));
                }
            }

            @Override
            public void onServiceStarted() {
                // No automatic action: desired state is controlled by MainActivity.
            }
        };
        volatile boolean ready;
        volatile boolean permissionGranted;
        volatile boolean shouldShowRationale;
        volatile int serverUid = -1;
        volatile int serverVersion = -1;
        boolean linked;
        boolean attaching;
        boolean dead;

        Connection(IBinder binder, String packageName) {
            this.binder = binder;
            this.service = IStellarService.Stub.asInterface(binder);
            this.packageName = packageName;
        }
    }

    private NativeStellar() {
    }

    public static void acceptBinder(IBinder newBinder, String clientPackageName) {
        if (newBinder == null || !newBinder.pingBinder()) return;
        pruneDeadConnections();
        // asInterface may query a local Binder; create the candidate outside the state lock.
        Connection candidate = new Connection(newBinder, clientPackageName);
        Connection attached;
        boolean link;
        synchronized (NativeStellar.class) {
            attached = CONNECTIONS.get(newBinder);
            if (attached == null) {
                attached = candidate;
                CONNECTIONS.put(newBinder, attached);
            }
            if (connection != attached) {
                connection = attached;
                connectionRevision++;
                clientCallback = attached.callback;
                attached.ready = false;
                attached.permissionGranted = false;
                attached.shouldShowRationale = false;
                attached.serverUid = -1;
                attached.serverVersion = -1;
            }
            if (attached.ready || attached.attaching) return;
            attached.attaching = true;
            link = !attached.linked;
        }
        try {
            if (link) {
                newBinder.linkToDeath(attached.deathRecipient, 0);
                synchronized (NativeStellar.class) {
                    if (attached.dead) return;
                    attached.linked = true;
                }
            }
            Bundle args = new Bundle();
            args.putInt(ATTACH_API_VERSION, CLIENT_API_VERSION);
            args.putString(ATTACH_PACKAGE_NAME, clientPackageName);
            // An admitted attach can finish after replacement; its source-scoped callback is
            // harmless while inactive and remains the service's registered client if revisited.
            attached.service.attachApplication(attached.callback, args);
        } catch (Throwable e) {
            clearBinder(attached);
        } finally {
            synchronized (NativeStellar.class) {
                attached.attaching = false;
            }
        }
    }

    public static void clearBinder() {
        clearBinder(connection);
    }

    private static void clearBinder(Connection expected) {
        long revision;
        synchronized (NativeStellar.class) {
            if (expected == null || connection != expected) return;
            connection = null;
            revision = ++connectionRevision;
        }
        notifyDead(revision);
    }

    private static void managerDied(Connection expected) {
        long revision = 0L;
        synchronized (NativeStellar.class) {
            if (CONNECTIONS.get(expected.binder) != expected) return;
            CONNECTIONS.remove(expected.binder);
            expected.dead = true;
            if (connection == expected) {
                connection = null;
                revision = ++connectionRevision;
            }
        }
        if (revision != 0L) notifyDead(revision);
    }

    private static void pruneDeadConnections() {
        Connection[] known;
        synchronized (NativeStellar.class) {
            known = CONNECTIONS.values().toArray(new Connection[0]);
        }
        for (Connection old : known) {
            // Local death knowledge only; never ping a manager while holding the state lock.
            if (!old.binder.isBinderAlive()) managerDied(old);
        }
    }

    private static void notifyDead(long revision) {
        for (BinderDeadListener listener : DEAD_LISTENERS) {
            MAIN_HANDLER.post(() -> {
                synchronized (NativeStellar.class) {
                    if (connectionRevision != revision) return;
                }
                listener.onBinderDead();
            });
        }
    }

    private static void postIfCurrent(Connection expected, Runnable callback) {
        MAIN_HANDLER.post(() -> {
            if (connection == expected) callback.run();
        });
    }

    public static boolean pingBinder() {
        Connection current = connection;
        return current != null && current.ready && current.binder.pingBinder()
                && connection == current;
    }

    public static boolean checkSelfPermission() {
        Connection current = connection;
        if (current == null) return false;
        try {
            boolean allowed = current.service.checkSelfPermission(PERMISSION_STELLAR);
            synchronized (NativeStellar.class) {
                if (connection != current) return false;
                current.permissionGranted = allowed;
                return allowed;
            }
        } catch (RemoteException e) {
            return false;
        }
    }

    public static boolean shouldShowRequestPermissionRationale() {
        Connection current = connection;
        if (current == null || current.permissionGranted) return false;
        try {
            boolean rationale = current.service.shouldShowRequestPermissionRationale();
            synchronized (NativeStellar.class) {
                if (connection != current) return false;
                current.shouldShowRationale = rationale;
            }
        } catch (RemoteException ignored) {
            // Keep the latest server-provided value.
        }
        return connection == current && current.shouldShowRationale;
    }

    public static void requestPermission(int requestCode) throws RemoteException {
        requireService().requestPermission(PERMISSION_STELLAR, requestCode);
    }

    public static int getServerUid() {
        Connection current = connection;
        return current == null ? -1 : current.serverUid;
    }

    public static int getServerVersion() {
        Connection current = connection;
        return current == null ? -1 : current.serverVersion;
    }

    public static IStellarService requireService() {
        Connection current = connection;
        if (current == null) throw new IllegalStateException("Stellar service is not connected");
        return current.service;
    }

    public static String getPackageName() {
        Connection current = connection;
        return current == null ? null : current.packageName;
    }

    public static IBinder getBinder() {
        Connection current = connection;
        return current == null ? null : current.binder;
    }

    public static IBinder getClientBinder() {
        // This is the exact callback sent to the current manager. Retain a non-null last Binder
        // after disconnection for callers that previously obtained the process-wide identity.
        Connection current = connection;
        return (current == null ? clientCallback : current.callback).asBinder();
    }

    public static void addBinderReceivedListenerSticky(BinderReceivedListener listener) {
        RECEIVED_LISTENERS.add(listener);
        Connection current = connection;
        if (current != null && pingBinder()) postIfCurrent(current, listener::onBinderReceived);
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
