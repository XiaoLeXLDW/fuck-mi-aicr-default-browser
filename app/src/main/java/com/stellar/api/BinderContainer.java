/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package com.stellar.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible Parcelable used by Stellar's ContentProvider Binder transport. */
public final class BinderContainer implements Parcelable {
    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override
        public BinderContainer createFromParcel(Parcel source) {
            return new BinderContainer(source.readStrongBinder());
        }

        @Override
        public BinderContainer[] newArray(int size) {
            return new BinderContainer[size];
        }
    };

    public IBinder binder;

    public BinderContainer(IBinder binder) {
        this.binder = binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeStrongBinder(binder);
    }
}
