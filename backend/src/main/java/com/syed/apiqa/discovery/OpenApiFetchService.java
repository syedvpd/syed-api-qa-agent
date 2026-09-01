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
                // 1. Enforce strict SSRF & Anti-DNS Rebinding IP Pinning before connecting
                ValidatedTarget target = ssrfGuard.resolveAndValidate(currentUrl);

                URL url = URI.create(target.pinnedUrl()).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(false);

                // Set original virtual host header so web server routes properly while socket connects to pinned IP
                connection.setRequestProperty("Host", target.originalHostHeader());
                connection.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
                connection.setRequestProperty("Accept", "application/json, application/yaml, text/yaml, */*");

                // If HTTPS, configure SNI and verify certificate against original domain
                if (connection instanceof HttpsURLConnection httpsConn) {
                    SSLParameters sslParams = httpsConn.getSSLSocketFactory().getDefaultCipherSuites() != null
                            ? new SSLParameters() : null;
                    if (sslParams != null) {
                        sslParams.setServerNames(List.of(new SNIHostName(target.originalHost())));
                    }
                    httpsConn.setHostnameVerifier((hostname, session) ->
                            HttpsURLConnection.getDefaultHostnameVerifier().verify(target.originalHost(), session));
                }

                int statusCode = connection.getResponseCode();

                // Handle Redirects safely with re-validation of destination
                if (statusCode >= 300 && statusCode < 400) {
                    String redirectLocation = connection.getHeaderField("Location");
                    if (redirectLocation == null || redirectLocation.isBlank()) {
                        throw new IllegalStateException("HTTP redirect received without Location header");
                    }
                    URI redirectedUri = target.originalUri().resolve(redirectLocation);
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
