package dev.codex.mibrowserredirector;

import java.util.List;

/** Resolves UI selection without changing the running service's configured target. */
final class BrowserSelection {
    private BrowserSelection() {
    }

    static BrowserOption resolve(List<BrowserOption> options, String preferredPackage,
                                 String defaultPackage) {
        if (preferredPackage != null) {
            // A removed/disabled browser must not silently turn into a different target.
            return find(options, preferredPackage);
        }
        BrowserOption systemDefault = find(options, defaultPackage);
        return systemDefault != null ? systemDefault : options.isEmpty() ? null : options.get(0);
    }

    private static BrowserOption find(List<BrowserOption> options, String packageName) {
        for (BrowserOption option : options) {
            if (option.packageName.equals(packageName)) return option;
        }
        return null;
    }
}
