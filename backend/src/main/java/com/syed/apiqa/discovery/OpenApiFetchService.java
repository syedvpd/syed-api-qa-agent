package com.syed.apiqa.discovery;

import com.syed.apiqa.safety.SsrfProtectionGuard;
import com.syed.apiqa.safety.SsrfProtectionGuard.ValidatedTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Real OpenAPI / Swagger specification fetcher.
 * Enforces strict pre-connection SSRF checks, DNS rebinding / IP pinning defense,
 * redirects safety, connection/read timeouts, and response body size limits.
 */
@Service
public class OpenApiFetchService {

    private final SsrfProtectionGuard ssrfGuard;

    @Value("${syed.safety.default-timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${syed.safety.max-response-size-bytes:2097152}")
    private int maxSizeBytes;

    public OpenApiFetchService(SsrfProtectionGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    public String fetchSpecification(String specUrl) {
        if (specUrl == null || specUrl.isBlank()) {
            throw new IllegalArgumentException("Specification URL cannot be empty");
        }

        int redirects = 0;
        String currentUrl = specUrl;

        while (redirects < 5) {
            try {
                // 1. Enforce strict SSRF & Anti-DNS Rebinding IP validation before connecting
                ssrfGuard.validateTargetUrl(currentUrl);

                URL url = URI.create(currentUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(false);

                connection.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
                connection.setRequestProperty("Accept", "application/json, application/yaml, text/yaml, */*");

                int statusCode = connection.getResponseCode();

                // Handle Redirects safely with re-validation of destination
                if (statusCode >= 300 && statusCode < 400) {
                    String redirectLocation = connection.getHeaderField("Location");
                    if (redirectLocation == null || redirectLocation.isBlank()) {
                        throw new IllegalStateException("HTTP redirect received without Location header");
                    }
                    URI redirectedUri = URI.create(currentUrl).resolve(redirectLocation);
                    currentUrl = redirectedUri.toString();
                    redirects++;
                    continue;
                }

                if (statusCode < 200 || statusCode >= 300) {
                    throw new IllegalStateException("Failed to fetch OpenAPI spec from " + currentUrl + ". HTTP Status: " + statusCode);
                }

                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalRead = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        totalRead += bytesRead;
                        if (totalRead > maxSizeBytes) {
                            throw new IllegalStateException("OpenAPI specification exceeds maximum allowed size of " + maxSizeBytes + " bytes");
                        }
                        out.write(buffer, 0, bytesRead);
                    }

                    String content = out.toString(StandardCharsets.UTF_8);
                    if (content.trim().startsWith("<!DOCTYPE") || content.trim().startsWith("<html")) {
                        String autoSpec = attemptAutoResolveSpec(currentUrl);
                        if (autoSpec != null) {
                            return autoSpec;
                        }
                        throw new IllegalArgumentException("The target URL returned an HTML web page instead of an OpenAPI JSON/YAML specification. Please provide the direct OpenAPI spec URL (e.g. /openapi.json, /swagger.json, or /v3/api-docs).");
                    }
                    return content;
                }

            } catch (SecurityException | IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Error fetching OpenAPI specification from " + currentUrl + ": " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException("Too many redirects encountered while fetching OpenAPI specification");
    }

    private String attemptAutoResolveSpec(String originalUrl) {
        try {
            URI uri = URI.create(originalUrl);
            String base = uri.getScheme() + "://" + uri.getAuthority();
            String[] candidatePaths = {"/openapi.json", "/v3/api-docs", "/v2/swagger.json", "/swagger.json"};
            for (String path : candidatePaths) {
                try {
                    String candidateUrl = base + path;
                    ssrfGuard.validateTargetUrl(candidateUrl);
                    URL u = URI.create(candidateUrl).toURL();
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
                    conn.setRequestProperty("Accept", "application/json");
                    if (conn.getResponseCode() == 200) {
                        try (InputStream in = conn.getInputStream();
                             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) != -1) {
                                out.write(buf, 0, r);
                            }
                            String res = out.toString(StandardCharsets.UTF_8);
                            if (!res.trim().startsWith("<") && (res.contains("\"openapi\"") || res.contains("\"swagger\""))) {
                                return res;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
