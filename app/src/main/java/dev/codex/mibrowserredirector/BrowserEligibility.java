package dev.codex.mibrowserredirector;

/** Classification is independent of app names, icons, and the user's current selection. */
final class BrowserEligibility {
    private BrowserEligibility() {
    }

    static boolean accepts(String packageName, String ownPackage,
                           boolean genericHttp, boolean genericHttps) {
        return packageName != null && !packageName.equals(ownPackage)
                && !packageName.equals("com.android.browser") && genericHttp && genericHttps;
    }

    static boolean isGenericFilter(boolean viewAction, boolean browsable, boolean defaultCategory,
                                   boolean matchesScheme, int authorities, int paths,
                                   int schemeSpecificParts, int mimeTypes) {
        return viewAction && browsable && defaultCategory && matchesScheme
                && authorities == 0 && paths == 0 && schemeSpecificParts == 0 && mimeTypes == 0;
    }
}
