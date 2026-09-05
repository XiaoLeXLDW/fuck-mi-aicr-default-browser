package dev.codex.mibrowserredirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class UrlExtractorTest {
    @Test
    public void acceptsDirectHttpsUrl() {
        assertEquals("https://example.com/a?b=1", UrlExtractor.extract("https://example.com/a?b=1"));
    }

    @Test
    public void acceptsDirectHttpUrl() {
        assertEquals("http://example.com", UrlExtractor.extract("http://example.com"));
    }

    @Test
    public void acceptsUnicodeInternationalizedDomainName() {
        String url = "https://例子.测试/path";

        assertEquals(url, UrlExtractor.extract(url));
        assertEquals("例子.测试", UrlExtractor.displayHost(url));
    }

    @Test
    public void rejectsMalformedInternationalizedDomainName() {
        assertNull(UrlExtractor.extract("https://例子..测试/path"));
    }

    @Test
    public void extractsEncodedUrlFromMiBrowserWrapper() {
        assertEquals(
                "https://example.com/path?q=hello",
                UrlExtractor.extract("mibrowser://open?url=https%3A%2F%2Fexample.com%2Fpath%3Fq%3Dhello")
        );
    }

    @Test
    public void extractsNestedRedirect() {
        assertEquals(
                "https://example.org/final",
                UrlExtractor.extract("mibrowser://open?target=mibrowser%3A%2F%2Fnext%3Furl%3Dhttps%253A%252F%252Fexample.org%252Ffinal")
        );
    }

    @Test
    public void rejectsDangerousSchemes() {
        assertNull(UrlExtractor.extract("intent://example.com/#Intent;scheme=https;end"));
        assertNull(UrlExtractor.extract("javascript:alert(1)"));
        assertNull(UrlExtractor.extract("file:///data/local/tmp/a"));
    }

    @Test
    public void rejectsMissingHostAndRandomText() {
        assertNull(UrlExtractor.extract("https:///missing-host"));
        assertNull(UrlExtractor.extract("not a url"));
    }

    @Test
    public void onlyUsesKnownWrapperKeys() {
        assertNull(UrlExtractor.extract("mibrowser://open?token=https%3A%2F%2Fexample.com"));
    }
}
