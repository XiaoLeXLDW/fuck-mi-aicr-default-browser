package dev.codex.mibrowserredirector;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BrowserSelectionTest {
    private final BrowserOption firefox = new BrowserOption("Firefox", "org.mozilla.firefox");
    private final BrowserOption chrome = new BrowserOption("Chrome", "com.android.chrome");

    @Test
    public void explicitSelectionBeatsSystemDefault() {
        assertSame(firefox, BrowserSelection.resolve(List.of(chrome, firefox),
                firefox.packageName, chrome.packageName));
    }

    @Test
    public void refreshKeepsPackageEvenWhenOrderAndLabelChange() {
        BrowserOption renamed = new BrowserOption("Firefox Browser", firefox.packageName);
        assertSame(renamed, BrowserSelection.resolve(List.of(renamed, chrome),
                firefox.packageName, chrome.packageName));
    }

    @Test
    public void removedBrowserDoesNotSilentlySelectAnother() {
        assertNull(BrowserSelection.resolve(List.of(chrome), firefox.packageName, chrome.packageName));
    }

    @Test
    public void reinstalledBrowserRestoresPreviousSelection() {
        assertNull(BrowserSelection.resolve(List.of(), firefox.packageName, null));
        assertSame(firefox, BrowserSelection.resolve(List.of(chrome, firefox),
                firefox.packageName, chrome.packageName));
    }

    @Test
    public void firstUsePrefersAnAvailableSystemDefault() {
        assertSame(firefox, BrowserSelection.resolve(List.of(chrome, firefox), null, firefox.packageName));
    }

    @Test
    public void firstUseFallsBackWhenSystemDefaultIsExcluded() {
        assertSame(chrome, BrowserSelection.resolve(List.of(chrome, firefox), null, "com.android.browser"));
    }

    @Test
    public void noBrowsersMeansNoSelection() {
        assertNull(BrowserSelection.resolve(List.of(), null, chrome.packageName));
    }

    @Test
    public void duplicateNamesRemainDistinctByPackage() {
        BrowserOption otherFirefox = new BrowserOption("Firefox", "org.mozilla.fenix");
        assertSame(otherFirefox, BrowserSelection.resolve(List.of(firefox, otherFirefox),
                otherFirefox.packageName, null));
    }
}
