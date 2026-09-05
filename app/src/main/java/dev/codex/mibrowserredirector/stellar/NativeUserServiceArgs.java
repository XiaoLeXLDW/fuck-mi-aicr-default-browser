/*
 * Derived from Stellar-API e22b3a0c76305c57a36696b069938d3c356a290b.
 * Stellar modifications: Mozilla Public License 2.0.
 * Inherited Shizuku portions: Apache License 2.0, as declared upstream.
 * Local changes to this derived file are provided under MPL-2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/ for sources, changes and full terms.
 */
package dev.codex.mibrowserredirector.stellar;

import android.os.Bundle;

public final class NativeUserServiceArgs {
    private static final String ARG_PACKAGE = "stellar:userservice-package";
    private static final String ARG_CLASS = "stellar:userservice-class";
    private static final String ARG_PROCESS_SUFFIX = "stellar:userservice-process-suffix";
    private static final String ARG_DEBUG = "stellar:userservice-debug";
    private static final String ARG_USE_32_BIT = "stellar:userservice-use32bit";
    private static final String ARG_VERSION = "stellar:userservice-version";
    private static final String ARG_SERVICE_MODE = "stellar:userservice-mode";
    private static final String ARG_VERIFICATION_TOKEN =
            "stellar:userservice-verification-token";

    private final String className;
    private final String processNameSuffix;
    private final long versionCode;
    private final String verificationToken;

    public NativeUserServiceArgs(Class<?> serviceClass, String processNameSuffix,
            long versionCode, String verificationToken) {
        this.className = serviceClass.getName();
        this.processNameSuffix = processNameSuffix;
        this.versionCode = versionCode;
        this.verificationToken = verificationToken;
    }

    Bundle toBundle(String packageName) {
        Bundle bundle = new Bundle();
        bundle.putString(ARG_PACKAGE, packageName);
        bundle.putString(ARG_CLASS, className);
        bundle.putString(ARG_PROCESS_SUFFIX, processNameSuffix);
        bundle.putBoolean(ARG_DEBUG, false);
        bundle.putBoolean(ARG_USE_32_BIT, false);
        bundle.putLong(ARG_VERSION, versionCode);
        bundle.putInt(ARG_SERVICE_MODE, 1); // ServiceMode.DAEMON
        bundle.putString(ARG_VERIFICATION_TOKEN, verificationToken);
        return bundle;
    }

    String key() {
        return className + ":" + processNameSuffix;
    }

    String verificationToken() {
        return verificationToken;
    }
}
