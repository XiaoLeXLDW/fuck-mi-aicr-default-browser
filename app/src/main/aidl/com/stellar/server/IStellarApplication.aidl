/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package com.stellar.server;

interface IStellarApplication {
    oneway void bindApplication(in Bundle data) = 1;
    oneway void dispatchRequestPermissionResult(int requestCode, in Bundle data) = 2;
    oneway void onServiceStarted() = 3;
}
