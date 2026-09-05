package dev.codex.mibrowserredirector;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowserEligibilityTest {
    private static final String OWN = "dev.codex.mibrowserredirector";

    @Test
    public void domainSpecificWebHandlerIsNotABrowser() {
        // Synthetic resolver metadata, not a capture of any app installed on the user's phone.
        boolean generic = BrowserEligibility.isGenericFilter(true, true, true, true, 1, 0, 0, 0);
        assertFalse(BrowserEligibility.accepts("example.shopping", OWN, generic, generic));
    }

    @Test
    public void httpsOnlyHandlerIsNotAGeneralBrowser() {
        assertFalse(BrowserEligibility.accepts("example.video", OWN, false, true));
    }

    @Test
    public void httpOnlyHandlerIsNotAGeneralBrowser() {
        assertFalse(BrowserEligibility.accepts("example.download", OWN, true, false));
    }

    @Test
    public void bothGenericSchemesAreAcceptedWithoutAnAllowlist() {
        assertTrue(BrowserEligibility.accepts("org.mozilla.firefox", OWN, true, true));
        assertTrue(BrowserEligibility.accepts("com.android.chrome", OWN, true, true));
        assertTrue(BrowserEligibility.accepts("example.new.browser", OWN, true, true));
    }

    @Test
    public void selfAndXiaomiStayExcluded() {
        assertFalse(BrowserEligibility.accepts(OWN, OWN, true, true));
        assertFalse(BrowserEligibility.accepts("com.android.browser", OWN, true, true));
        assertFalse(BrowserEligibility.accepts(null, OWN, true, true));
    }

    @Test
    public void browserLookingNameDoesNotBypassCapabilityChecks() {
        assertFalse(BrowserEligibility.accepts("example.chrome.browser", OWN, false, false));
    }

    @Test
    public void genericWebFilterIsAccepted() {
        assertTrue(BrowserEligibility.isGenericFilter(true, true, true, true, 0, 0, 0, 0));
    }

    @Test
    public void hostWildcardAndDomainRestrictionsAreRejected() {
        // Even a wildcard authority remains a host-scoped declaration.
        assertFalse(BrowserEligibility.isGenericFilter(true, true, true, true, 1, 0, 0, 0));
    }

    @Test
    public void pathSchemeSpecificAndMimeRestrictionsAreRejected() {
        assertFalse(BrowserEligibility.isGenericFilter(true, true, true, true, 0, 1, 0, 0));
        assertFalse(BrowserEligibility.isGenericFilter(true, true, true, true, 0, 0, 1, 0));
        assertFalse(BrowserEligibility.isGenericFilter(true, true, true, true, 0, 0, 0, 1));
    }

    @Test
    public void missingIntentRequirementsAreRejected() {
        assertFalse(BrowserEligibility.isGenericFilter(false, true, true, true, 0, 0, 0, 0));
        assertFalse(BrowserEligibility.isGenericFilter(true, false, true, true, 0, 0, 0, 0));
        assertFalse(BrowserEligibility.isGenericFilter(true, true, false, true, 0, 0, 0, 0));
        assertFalse(BrowserEligibility.isGenericFilter(true, true, true, false, 0, 0, 0, 0));
    }
}
