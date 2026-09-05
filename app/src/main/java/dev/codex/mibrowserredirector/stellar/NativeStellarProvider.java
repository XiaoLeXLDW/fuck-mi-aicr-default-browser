/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package dev.codex.mibrowserredirector.stellar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import com.stellar.api.BinderContainer;
import com.stellar.server.IStellarService;

public final class NativeStellarProvider extends ContentProvider {
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String METHOD_GET_BINDER = "getBinder";
    private static final String METHOD_SEND_USER_SERVICE = "sendUserService";
    private static final String EXTRA_BINDER = "roro.stellar.manager.intent.extra.BINDER";
    private static final String EXTRA_CLIENT_BINDER =
            "roro.stellar.manager.intent.extra.CLIENT_BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());

        if (METHOD_SEND_BINDER.equals(method)) {
            BinderContainer container = readContainer(extras, EXTRA_BINDER);
            if (container != null && container.binder != null && getContext() != null) {
                NativeStellar.acceptBinder(container.binder, getContext().getPackageName());
            }
            return new Bundle();
        }

        if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = NativeStellar.getBinder();
            if (binder == null || !binder.pingBinder()) return null;
            Bundle reply = new Bundle();
            reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            return reply;
        }

        if (METHOD_SEND_USER_SERVICE.equals(method)) {
            BinderContainer container = readContainer(extras, EXTRA_BINDER);
            IStellarService service;
            try {
                service = NativeStellar.requireService();
                if (container == null || container.binder == null) return null;
                service.attachUserService(container.binder, extras);
            } catch (Exception e) {
                return null;
            }
            Bundle reply = new Bundle();
            IBinder stellarBinder = NativeStellar.getBinder();
            if (stellarBinder != null) {
                reply.putParcelable(EXTRA_BINDER, new BinderContainer(stellarBinder));
            }
            reply.putParcelable(EXTRA_CLIENT_BINDER,
                    new BinderContainer(NativeStellar.getClientBinder()));
            return reply;
        }
        return super.call(method, arg, extras);
    }

    @SuppressWarnings("deprecation")
    private static BinderContainer readContainer(Bundle bundle, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return bundle.getParcelable(key, BinderContainer.class);
        }
        return bundle.getParcelable(key);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
