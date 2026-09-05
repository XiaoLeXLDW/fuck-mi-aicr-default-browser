/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package com.stellar.server;

interface IUserServiceCallback {
    oneway void onServiceConnected(in IBinder service, String verificationToken) = 1;
    oneway void onServiceDisconnected() = 2;
    oneway void onServiceStartFailed(int errorCode, String message) = 3;
}
