package com.syed.apiqa.discovery;

import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Real OpenAPI / Swagger specification fetcher.
 * Enforces strict pre-connection SSRF checks, redirects safety, connection/read timeouts,
 * and response body size limits.
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

        // 1. Enforce strict SSRF protection before opening any connection
        ssrfGuard.validateTargetUrl(specUrl);

        int redirects = 0;
        String currentUrl = specUrl;

        while (redirects < 5) {
            try {
                URI uri = URI.create(currentUrl);
                URL url = uri.toURL();

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
                connection.setRequestProperty("Accept", "application/json, application/yaml, text/yaml, */*");

                int statusCode = connection.getResponseCode();

                // Handle Redirects safely with re-validation
                if (statusCode >= 300 && statusCode < 400) {
                    String redirectLocation = connection.getHeaderField("Location");
                    if (redirectLocation == null || redirectLocation.isBlank()) {
                        throw new IllegalStateException("HTTP redirect received without Location header");
                    }
                    URI redirectedUri = uri.resolve(redirectLocation);
                    currentUrl = redirectedUri.toString();
                    // Re-validate the redirected destination with SSRF guard!
                    ssrfGuard.validateTargetUrl(currentUrl);
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

                    return out.toString(StandardCharsets.UTF_8);
                }

            } catch (SecurityException | IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Error fetching OpenAPI specification from " + currentUrl + ": " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException("Too many redirects encountered while fetching OpenAPI specification");
    }
}
