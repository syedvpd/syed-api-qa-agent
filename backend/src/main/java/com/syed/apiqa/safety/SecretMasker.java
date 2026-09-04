package com.syed.apiqa.safety;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Redacts secrets, auth tokens, API keys, passwords, and sensitive cookies
 * from headers, parameters, and payloads before storage or logging.
 */
@Component
public class SecretMasker {

    public static final String REDACTED_MARKER = "••••••••";

    private static final Set<String> SENSITIVE_HEADERS = new HashSet<>(Arrays.asList(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "cookie",
            "set-cookie",
            "api-key",
            "apikey",
            "token",
            "x-auth-token"
    ));

    private static final Pattern SENSITIVE_JSON_KEYS = Pattern.compile(
            "(?i)\"(password|secret|token|apiKey|api_key|access_token|client_secret|cvv|credit_card)\"\\s*:\\s*\"[^\"]*\""
    );

    public Map<String, List<String>> maskHeaders(Map<String, List<String>> headers) {
        if (headers == null) return Collections.emptyMap();

        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            if (headerName == null) {
                sanitized.put("Status-Line", entry.getValue());
                continue;
            }
            if (SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
                sanitized.put(headerName, Collections.singletonList(REDACTED_MARKER));
            } else {
                sanitized.put(headerName, entry.getValue());
            }
        }
        return sanitized;
    }

    public String maskHeader(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) return headerValue;
        if (SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
            if (headerName.equalsIgnoreCase("authorization") && headerValue.startsWith("Bearer ")) {
                return "Bearer syed_••••••••";
            }
            return "••••••••";
        }
        return headerValue;
    }

    public String maskBody(String body) {
        if (body == null || body.isBlank()) return body;
        return SENSITIVE_JSON_KEYS.matcher(body).replaceAll("\"$1\":\"" + REDACTED_MARKER + "\"");
    }

    private static final Set<String> SENSITIVE_QUERY_PARAMS = new HashSet<>(Arrays.asList(
            "token", "access_token", "refresh_token", "api_key", "apikey", "key",
            "secret", "client_secret", "password", "passwd", "signature", "sig", "auth"
    ));

    /**
     * Redacts sensitive query-string parameter values from a URL/URI string.
     * Example: {@code ?token=abc&foo=1} becomes {@code ?token=[REDACTED]&foo=1}.
     * Returns the input unchanged if it is not a URL-like string.
     */
    public String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String result = url;
        for (String param : SENSITIVE_QUERY_PARAMS) {
            result = result.replaceAll("(?i)([?&]" + Pattern.quote(param) + "=)[^&#]*", "$1" + REDACTED_MARKER);
        }
        return result;
    }
}
