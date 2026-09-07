package dev.codex.mibrowserredirector.stellar;
import com.stellar.server.IStellarService;
public final class NativeStellar {
    public static volatile IStellarService service;
    public static String getPackageName() { return "test.app"; }
    public static IStellarService requireService() { return service; }
}
