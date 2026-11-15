package io.givops.ticketing.youtrack;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class YouTrackBaseUrls {
    private static final int MAX_BASE_URL_LENGTH = 255;
    private static final Pattern SINGLE_CONTEXT_SEGMENT = Pattern.compile("^/([A-Za-z0-9._-]+?)/?$");
    private static final Set<String> RESERVED_YOUTRACK_SEGMENTS = Set.of(
            "api", "issue", "issues", "projects", "admin", "rest");

    private YouTrackBaseUrls() {}

    public static URI normalizeYouTrackBaseUrl(URI value, boolean allowCustomPort,
                                              Supplier<? extends RuntimeException> onInvalid) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw onInvalid.get();
        }
        int port = value.getPort();
        if (allowCustomPort ? (port == 0 || port > 65535) : (port != -1 && port != 443)) {
            throw onInvalid.get();
        }
        String host = value.getHost();
        String expectedAuthority = host + (port == -1 ? "" : ":" + port);
        if (!expectedAuthority.equalsIgnoreCase(value.getRawAuthority())) {
            throw onInvalid.get();
        }
        String contextPath = normalizedYouTrackContextPath(value.getRawPath(), onInvalid);
        String portSuffix = port == -1 || port == 443 ? "" : ":" + port;
        return checkLength("https://" + host.toLowerCase(Locale.ROOT) + portSuffix + contextPath, onInvalid);
    }

    public static String contextPath(URI baseUrl) {
        if (baseUrl == null || baseUrl.getRawPath() == null || baseUrl.getRawPath().isEmpty()
                || "/".equals(baseUrl.getRawPath())) {
            return "";
        }
        String raw = baseUrl.getRawPath();
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static String normalizedYouTrackContextPath(String rawPath, Supplier<? extends RuntimeException> onInvalid) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "";
        }
        var match = SINGLE_CONTEXT_SEGMENT.matcher(rawPath);
        if (!match.matches()) {
            throw onInvalid.get();
        }
        String segment = match.group(1);
        if (".".equals(segment) || "..".equals(segment)
                || RESERVED_YOUTRACK_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
            throw onInvalid.get();
        }
        return "/" + segment;
    }

    private static URI checkLength(String normalized, Supplier<? extends RuntimeException> onInvalid) {
        if (normalized.length() > MAX_BASE_URL_LENGTH) {
            throw onInvalid.get();
        }
        return URI.create(normalized);
    }
}
