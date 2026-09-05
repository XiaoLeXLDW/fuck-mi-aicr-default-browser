package dev.codex.mibrowserredirector.privilege;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.codex.mibrowserredirector.RedirectorUserService;
import dev.codex.mibrowserredirector.ServiceIdentity;

/**
 * Owns the seam between the UI lifecycle and the supported privilege managers.
 * A {@link ServiceSession} captures the adapter that created it, so removal can
 * never be accidentally routed through another backend.
 */
public final class PrivilegeRuntime implements AutoCloseable {
    public enum BackendId {
        STELLAR("stellar", "兼容服务（原生 API）"),
        SHIZUKU("shizuku", "Shizuku 服务");

        private final String storageValue;
        private final String displayName;

        BackendId(String storageValue, String displayName) {
            this.storageValue = storageValue;
            this.displayName = displayName;
        }

        public String storageValue() {
            return storageValue;
        }

        public String displayName() {
            return displayName;
        }

        public static BackendId fromStorage(String value) {
            if (value == null) return null;
            for (BackendId id : values()) {
                if (id.storageValue.equals(value)) return id;
            }
            return null;
        }
    }

    public enum Preference {
        AUTO("auto", "自动（推荐）", null),
        SHIZUKU("shizuku", "Shizuku 服务", BackendId.SHIZUKU),
        STELLAR("stellar", "兼容服务（原生 API）", BackendId.STELLAR);

        private final String storageValue;
        private final String displayName;
        private final BackendId explicitBackend;

        Preference(String storageValue, String displayName, BackendId explicitBackend) {
            this.storageValue = storageValue;
            this.displayName = displayName;
            this.explicitBackend = explicitBackend;
        }

        public String storageValue() {
            return storageValue;
        }

        BackendId explicitBackend() {
            return explicitBackend;
        }

        public static Preference fromStorage(String value) {
            if (value != null) {
                for (Preference preference : values()) {
                    if (preference.storageValue.equals(value)) return preference;
                }
            }
            return AUTO;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class BackendSnapshot {
        private final BackendId id;
        private final boolean available;
        private final boolean authorized;
        private final boolean permissionRationale;
        private final int serverUid;
        private final int serverVersion;

        BackendSnapshot(BackendId id, boolean available, boolean authorized,
                boolean permissionRationale, int serverUid, int serverVersion) {
            this.id = Objects.requireNonNull(id);
            this.available = available;
            this.authorized = authorized;
            this.permissionRationale = permissionRationale;
            this.serverUid = serverUid;
            this.serverVersion = serverVersion;
        }

        public BackendId id() {
            return id;
        }

        public boolean available() {
            return available;
        }

        public boolean authorized() {
            return authorized;
        }

        public boolean permissionRationale() {
            return permissionRationale;
        }

        public int serverUid() {
            return serverUid;
        }

        public int serverVersion() {
            return serverVersion;
        }
    }

    public interface Listener {
        void onAvailabilityChanged(BackendId backendId, boolean available);

        void onPermissionResult(BackendId backendId, int requestCode, boolean allowed);
    }

    public interface ServiceCallback {
        void onServiceConnected(ServiceSession session, IBinder binder);

        void onServiceDisconnected(ServiceSession session);

        void onServiceStartFailed(ServiceSession session, int errorCode, String message);
    }

    public interface ServiceSession {
        BackendId backendId();

        void remove() throws Exception;

        void detach();

        boolean isCurrent();
    }

    interface BackendAdapter {
        BackendId id();

        BackendSnapshot snapshot();

        void startObserving(Listener listener);

        void stopObserving();

        void requestPermission(int requestCode) throws Exception;

        ServiceSession bind(ServiceCallback callback);
    }

    private final Map<BackendId, BackendAdapter> adapters = new EnumMap<>(BackendId.class);
    private boolean observing;

    public static PrivilegeRuntime create(Context context, Handler callbackHandler,
            String stellarVerificationToken) {
        Context appContext = context.getApplicationContext();
        return new PrivilegeRuntime(
                new StellarBackendAdapter(
                        callbackHandler,
                        RedirectorUserService.class,
                        ServiceIdentity.STELLAR_PROCESS_SUFFIX,
                        ServiceIdentity.USER_SERVICE_GENERATION,
                        stellarVerificationToken
                ),
                new ShizukuBackendAdapter(
                        appContext,
                        callbackHandler,
                        RedirectorUserService.class,
                        ServiceIdentity.SHIZUKU_PROCESS_SUFFIX,
                        ServiceIdentity.SHIZUKU_SERVICE_TAG,
                        ServiceIdentity.USER_SERVICE_GENERATION
                )
        );
    }

    PrivilegeRuntime(BackendAdapter... configuredAdapters) {
        for (BackendAdapter adapter : configuredAdapters) {
            BackendAdapter previous = adapters.put(adapter.id(), adapter);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate backend " + adapter.id());
            }
        }
    }

    public void startObserving(Listener listener) {
        if (observing) throw new IllegalStateException("PrivilegeRuntime is already observing");
        observing = true;
        for (BackendAdapter adapter : adapters.values()) {
            adapter.startObserving(listener);
        }
    }

    /**
     * Resolves a backend exactly once for an operation. A pinned backend always
     * wins, even while unavailable, so a running session never silently changes owner.
     */
    public BackendSnapshot choose(Preference preference, BackendId pinnedBackend) {
        if (pinnedBackend != null) return snapshot(pinnedBackend);
        BackendId explicit = preference.explicitBackend();
        if (explicit != null) return snapshot(explicit);

        // Stellar can also publish a Shizuku-compatible Binder. Prefer its native
        // adapter when both are visible so the proven token-based stop path is kept.
        BackendSnapshot stellar = snapshot(BackendId.STELLAR);
        if (stellar.available()) return stellar;
        BackendSnapshot shizuku = snapshot(BackendId.SHIZUKU);
        return shizuku.available() ? shizuku : null;
    }

    public BackendSnapshot snapshot(BackendId backendId) {
        BackendAdapter adapter = requireAdapter(backendId);
        try {
            return adapter.snapshot();
        } catch (RuntimeException error) {
            return new BackendSnapshot(backendId, false, false, false, -1, -1);
        }
    }

    /**
     * The Shizuku compatibility Binder exposed by Stellar and the official
     * Shizuku Binder share one process-wide static slot in Shizuku API. When
     * Stellar's native Binder is also alive, that slot has no trustworthy
     * provider identity, so starting or re-attaching a Shizuku-owned service
     * would make backend-specific removal ambiguous.
     */
    public boolean hasAmbiguousShizukuSource(BackendId backendId) {
        return backendId == BackendId.SHIZUKU
                && snapshot(BackendId.STELLAR).available();
    }

    public List<BackendSnapshot> snapshots() {
        List<BackendSnapshot> result = new ArrayList<>();
        result.add(snapshot(BackendId.SHIZUKU));
        result.add(snapshot(BackendId.STELLAR));
        return result;
    }

    public void requestPermission(BackendId backendId, int requestCode) throws Exception {
        requireAdapter(backendId).requestPermission(requestCode);
    }

    public ServiceSession bind(BackendId backendId, ServiceCallback callback) {
        return requireAdapter(backendId).bind(callback);
    }

    @Override
    public void close() {
        if (!observing) return;
        observing = false;
        for (BackendAdapter adapter : adapters.values()) {
            adapter.stopObserving();
        }
    }

    private BackendAdapter requireAdapter(BackendId backendId) {
        BackendAdapter adapter = adapters.get(backendId);
        if (adapter == null) throw new IllegalArgumentException("Unknown backend " + backendId);
        return adapter;
    }
}
