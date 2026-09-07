package dev.codex.mibrowserredirector;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.DisplayCutout;
import android.graphics.Insets;

/** Fixed visual top spacing is a floor; larger actual system safe areas still win. */
final class PageInsets {
    private PageInsets() { }

    static void install(View root, int fixedTop) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }
            // Absolute padding from this dispatch, never cumulatively add repeated insets.
            view.setPadding(left, Math.max(fixedTop, top), right, bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }
}
