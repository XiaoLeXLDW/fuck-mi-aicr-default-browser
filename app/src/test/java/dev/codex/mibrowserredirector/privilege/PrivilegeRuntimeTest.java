package dev.codex.mibrowserredirector.privilege;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;

import org.junit.Test;

import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendId;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.BackendSnapshot;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.Preference;
import dev.codex.mibrowserredirector.privilege.PrivilegeRuntime.ServiceSession;

public final class PrivilegeRuntimeTest {
    @Test
    public void autoPrefersNativeStellarWhenBothBindersAreAvailable() {
        FakeBackend stellar = new FakeBackend(BackendId.STELLAR, true, true);
        FakeBackend shizuku = new FakeBackend(BackendId.SHIZUKU, true, true);
        PrivilegeRuntime runtime = new PrivilegeRuntime(shizuku, stellar);

        BackendSnapshot selected = runtime.choose(Preference.AUTO, null);

        assertEquals(BackendId.STELLAR, selected.id());
    }

    @Test
    public void autoFallsBackToShizukuWhenNativeStellarIsUnavailable() {
        FakeBackend stellar = new FakeBackend(BackendId.STELLAR, false, false);
        FakeBackend shizuku = new FakeBackend(BackendId.SHIZUKU, true, true);
        PrivilegeRuntime runtime = new PrivilegeRuntime(stellar, shizuku);

        BackendSnapshot selected = runtime.choose(Preference.AUTO, null);

        assertEquals(BackendId.SHIZUKU, selected.id());
    }

    @Test
    public void autoReturnsNoBackendWhenNeitherManagerIsAvailable() {
        PrivilegeRuntime runtime = new PrivilegeRuntime(
                new FakeBackend(BackendId.STELLAR, false, false),
                new FakeBackend(BackendId.SHIZUKU, false, false));

        assertNull(runtime.choose(Preference.AUTO, null));
    }

    @Test
    public void pinnedOwnerNeverFallsThroughToAnotherAvailableBackend() {
        FakeBackend stellar = new FakeBackend(BackendId.STELLAR, true, true);
        FakeBackend shizuku = new FakeBackend(BackendId.SHIZUKU, false, false);
        PrivilegeRuntime runtime = new PrivilegeRuntime(stellar, shizuku);

        BackendSnapshot selected = runtime.choose(Preference.AUTO, BackendId.SHIZUKU);

        assertEquals(BackendId.SHIZUKU, selected.id());
        assertFalse(selected.available());
    }

    @Test
    public void shizukuSourceIsAmbiguousWhileNativeStellarIsAlive() {
        PrivilegeRuntime runtime = new PrivilegeRuntime(
                new FakeBackend(BackendId.STELLAR, true, true),
                new FakeBackend(BackendId.SHIZUKU, true, true));

        assertTrue(runtime.hasAmbiguousShizukuSource(BackendId.SHIZUKU));
        assertFalse(runtime.hasAmbiguousShizukuSource(BackendId.STELLAR));
    }

    @Test
    public void serviceSessionRemovesThroughTheAdapterThatCreatedIt() throws Exception {
        FakeBackend stellar = new FakeBackend(BackendId.STELLAR, true, true);
        FakeBackend shizuku = new FakeBackend(BackendId.SHIZUKU, true, true);
        PrivilegeRuntime runtime = new PrivilegeRuntime(stellar, shizuku);

        ServiceSession session = runtime.bind(BackendId.SHIZUKU, NO_OP_CALLBACK);
        session.remove();

        assertEquals(BackendId.SHIZUKU, session.backendId());
        assertEquals(1, shizuku.bindCount);
        assertEquals(1, shizuku.removeCount);
        assertEquals(0, stellar.bindCount);
        assertEquals(0, stellar.removeCount);
    }

    @Test
    public void observingRegistersAndUnregistersEveryAdapter() {
        FakeBackend stellar = new FakeBackend(BackendId.STELLAR, true, true);
        FakeBackend shizuku = new FakeBackend(BackendId.SHIZUKU, true, true);
        PrivilegeRuntime runtime = new PrivilegeRuntime(stellar, shizuku);

        runtime.startObserving(NO_OP_LISTENER);
        assertTrue(stellar.observing);
        assertTrue(shizuku.observing);

        runtime.close();
        assertFalse(stellar.observing);
        assertFalse(shizuku.observing);
    }

    private static final PrivilegeRuntime.Listener NO_OP_LISTENER =
            new PrivilegeRuntime.Listener() {
                @Override
                public void onAvailabilityChanged(BackendId backendId, boolean available) {
                }

                @Override
                public void onPermissionResult(BackendId backendId, int requestCode,
                        boolean allowed) {
                }
            };

    private static final PrivilegeRuntime.ServiceCallback NO_OP_CALLBACK =
            new PrivilegeRuntime.ServiceCallback() {
                @Override
                public void onServiceConnected(ServiceSession session, IBinder binder) {
                }

                @Override
                public void onServiceDisconnected(ServiceSession session) {
                }

                @Override
                public void onServiceStartFailed(ServiceSession session, int errorCode,
                        String message) {
                }
            };

    private static final class FakeBackend implements PrivilegeRuntime.BackendAdapter {
        private final BackendId id;
        private boolean available;
        private boolean authorized;
        private boolean observing;
        private int bindCount;
        private int removeCount;

        FakeBackend(BackendId id, boolean available, boolean authorized) {
            this.id = id;
            this.available = available;
            this.authorized = authorized;
        }

        @Override
        public BackendId id() {
            return id;
        }

        @Override
        public BackendSnapshot snapshot() {
            return new BackendSnapshot(id, available, authorized, false, 2000, 13);
        }

        @Override
        public void startObserving(PrivilegeRuntime.Listener listener) {
            observing = true;
        }

        @Override
        public void stopObserving() {
            observing = false;
        }

        @Override
        public void requestPermission(int requestCode) {
        }

        @Override
        public ServiceSession bind(PrivilegeRuntime.ServiceCallback callback) {
            bindCount++;
            return new ServiceSession() {
                @Override
                public BackendId backendId() {
                    return id;
                }

                @Override
                public void remove() {
                    removeCount++;
                }

                @Override
                public void detach() {
                }

                @Override
                public boolean isCurrent() {
                    return true;
                }
            };
        }
    }
}
