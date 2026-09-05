package dev.codex.mibrowserredirector;

interface IRedirectorService {
    String enable(String targetPackage, boolean observeOnly) = 1;
    String disable() = 2;
    boolean isEnabled() = 3;
    String getState() = 4;
    String getLastEvent() = 5;
    int getProtocolVersion() = 6;
    int getServiceGeneration() = 7;
    void destroy() = 16777114;
}
