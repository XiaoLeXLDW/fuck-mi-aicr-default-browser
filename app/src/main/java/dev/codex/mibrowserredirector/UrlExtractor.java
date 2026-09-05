package dev.codex.mibrowserredirector;

import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class UrlExtractor {
    private static final int MAX_NESTING = 4;
    private static final Set<String> URL_KEYS = new HashSet<>(Arrays.asList(
            "url", "u", "uri", "link", "target", "targeturl", "redirect", "redirect_url", "q"
    ));

    private UrlExtractor() {
    }

    static String extract(String dataString) {
        return extract(dataString, 0);
    }

    private static String extract(String candidate, int depth) {
        if (candidate == null || depth > MAX_NESTING) return null;
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > 16_384) return null;

        String direct = validateHttpUrl(value);
        if (direct != null) return direct;

        URI wrapper;
        try {
            wrapper = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            String decoded = decode(value);
            return decoded.equals(value) ? null : extract(decoded, depth + 1);
        }

        String rawQuery = wrapper.getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int equals = pair.indexOf('=');
                String rawKey = equals >= 0 ? pair.substring(0, equals) : pair;
                String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";
                String key = decode(rawKey).toLowerCase(Locale.ROOT);
                if (!URL_KEYS.contains(key)) continue;
                String nested = extract(decode(rawValue), depth + 1);
                if (nested != null) return nested;
            }
        }

        String decoded = decode(value);
        return decoded.equals(value) ? null : extract(decoded, depth + 1);
    }

    private static String validateHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            if (extractHost(uri) == null) return null;
            return value;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String extractHost(URI uri) {
        String host = uri.getHost();
        if (host != null && !host.trim().isEmpty()) return host;

        try {
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty()) return null;

            int userInfoEnd = authority.lastIndexOf('@');
            String hostAndPort = authority.substring(userInfoEnd + 1);
            if (hostAndPort.isEmpty() || hostAndPort.charAt(0) == '[') return null;

            int portSeparator = hostAndPort.lastIndexOf(':');
            String internationalizedHost = portSeparator < 0
                    ? hostAndPort
                    : hostAndPort.substring(0, portSeparator);
            if (portSeparator >= 0) {
                String port = hostAndPort.substring(portSeparator + 1);
                if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) return null;
            }
            if (internationalizedHost.isEmpty()) return null;

            String asciiHost = IDN.toASCII(internationalizedHost, IDN.USE_STD3_ASCII_RULES);
            if (asciiHost.isEmpty() || asciiHost.length() > 253) return null;
            return internationalizedHost;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    static String displayHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = extractHost(uri);
            return host == null ? "未知站点" : host;
        } catch (IllegalArgumentException ignored) {
            return "未知站点";
        }
    }
}
