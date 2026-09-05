package dev.codex.mibrowserredirector;

public final class ServiceIdentity {
    public static final String BUILD_LABEL = "v0.3.2";
    public static final int PROTOCOL_VERSION = 200;

    public static final String STELLAR_PROCESS_SUFFIX = "redirector";
    public static final String SHIZUKU_PROCESS_SUFFIX = "redirector_shizuku";
    public static final String SHIZUKU_SERVICE_TAG = "mi-browser-redirector";
    public static final int USER_SERVICE_GENERATION = 30_003;

    private ServiceIdentity() {
    }
}
