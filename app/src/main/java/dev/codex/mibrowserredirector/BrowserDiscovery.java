package dev.codex.mibrowserredirector;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BrowserDiscovery {
    private BrowserDiscovery() {
    }

    static List<ResolveInfo> queryHandlers(PackageManager pm, String scheme) {
        // Package-manager resolution is local; no request is sent to this example URL.
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://browser-discovery.invalid/"));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        return pm.queryIntentActivities(intent,
                PackageManager.MATCH_ALL | PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.GET_RESOLVED_FILTER);
    }

    static Set<String> genericPackages(List<ResolveInfo> handlers, String scheme) {
        Set<String> packages = new HashSet<>();
        for (ResolveInfo info : handlers) {
            if (info == null || info.activityInfo == null) continue;
            IntentFilter filter = info.filter;
            if (filter != null && BrowserEligibility.isGenericFilter(
                    filter.hasAction(Intent.ACTION_VIEW),
                    filter.hasCategory(Intent.CATEGORY_BROWSABLE),
                    filter.hasCategory(Intent.CATEGORY_DEFAULT),
                    filter.hasDataScheme(scheme),
                    filter.countDataAuthorities(), filter.countDataPaths(),
                    filter.countDataSchemeSpecificParts(), filter.countDataTypes())) {
                packages.add(info.activityInfo.packageName);
            }
        }
        return packages;
    }
}
