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
                SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(currentUrl);
                HttpResponseData resp = executePinnedGet(target, timeoutSeconds);

                // Handle Redirects safely with re-validation of destination
                if (resp.statusCode >= 300 && resp.statusCode < 400) {
                    String redirectLocation = resp.headers.get("location");
                    if (redirectLocation == null || redirectLocation.isBlank()) {
                        throw new IllegalStateException("HTTP redirect received without Location header");
                    }
                    URI redirectedUri = URI.create(currentUrl).resolve(redirectLocation);
                    currentUrl = redirectedUri.toString();
                    redirects++;
                    continue;
                }

                if (resp.statusCode < 200 || resp.statusCode >= 300) {
                    throw new IllegalStateException("Failed to fetch OpenAPI spec from " + currentUrl + ". HTTP Status: " + resp.statusCode);
                }

                String content = new String(resp.body, StandardCharsets.UTF_8);
                if (content.trim().startsWith("<!DOCTYPE") || content.trim().startsWith("<html")) {
                    String autoSpec = attemptAutoResolveSpec(currentUrl);
                    if (autoSpec != null) {
                        return autoSpec;
                    }
                    throw new IllegalArgumentException("The target URL returned an HTML web page instead of an OpenAPI JSON/YAML specification. Please provide the direct OpenAPI spec URL (e.g. /openapi.json, /swagger.json, or /v3/api-docs).");
                }
                return content;

            } catch (SecurityException | IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Error fetching OpenAPI specification from " + currentUrl + ": " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException("Too many redirects encountered while fetching OpenAPI specification");
    }

    private record HttpResponseData(int statusCode, java.util.Map<String, String> headers, byte[] body) {}

    private HttpResponseData executePinnedGet(SsrfProtectionGuard.ValidatedTarget target, int timeoutSec) throws Exception {
        boolean isHttps = "https".equalsIgnoreCase(target.originalUri().getScheme());
        int port = target.port() > 0 ? target.port() : (isHttps ? 443 : 80);
        java.net.InetAddress connectAddress = target.pinnedAddress() != null
                ? target.pinnedAddress()
                : java.net.InetAddress.getByName(target.originalHost());

        java.net.Socket rawSocket = new java.net.Socket();
        rawSocket.connect(new java.net.InetSocketAddress(connectAddress, port), timeoutSec * 1000);
        rawSocket.setSoTimeout(timeoutSec * 1000);

        java.net.Socket socket;
        if (isHttps) {
            javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) factory.createSocket(rawSocket, target.originalHost(), port, true);
            javax.net.ssl.SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(target.originalHost())));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();
            socket = sslSocket;
        } else {
            socket = rawSocket;
        }

        try (socket) {
            String path = target.originalUri().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (target.originalUri().getRawQuery() != null) path += "?" + target.originalUri().getRawQuery();

            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + target.originalHostHeader() + "\r\n"
                    + "User-Agent: Syed-API-QA-Agent/1.0\r\n"
                    + "Accept: application/json, application/yaml, text/yaml, */*\r\n"
                    + "Connection: close\r\n\r\n";

            socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            int statusCode = 200;
            if (statusLine != null) {
                String[] parts = statusLine.split(" ");
                if (parts.length >= 2) {
                    try { statusCode = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                }
            }

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
                }
            }

            byte[] body = readHttpBody(in, headers, maxSizeBytes);
            return new HttpResponseData(statusCode, headers, body);
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readHttpBody(InputStream in, java.util.Map<String, String> headers, int maxBytes) throws java.io.IOException {
        boolean isChunked = headers.getOrDefault("transfer-encoding", "").toLowerCase().contains("chunked");
        if (isChunked) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) break;
                sizeLine = sizeLine.trim();
                if (sizeLine.isEmpty()) continue;
                int semi = sizeLine.indexOf(';');
                if (semi > 0) sizeLine = sizeLine.substring(0, semi).trim();
                int chunkSize = Integer.parseInt(sizeLine, 16);
                if (chunkSize == 0) {
                    readLine(in);
                    break;
                }
                byte[] chunk = in.readNBytes(chunkSize);
                body.write(chunk);
                readLine(in);
                if (body.size() > maxBytes) {
                    throw new IllegalStateException("OpenAPI specification exceeds maximum allowed size of " + maxBytes + " bytes");
                }
            }
            return body.toByteArray();
        } else {
            String clStr = headers.get("content-length");
            if (clStr != null) {
                try {
                    int cl = Integer.parseInt(clStr.trim());
                    if (cl > maxBytes) {
                        throw new IllegalStateException("OpenAPI specification exceeds maximum allowed size of " + maxBytes + " bytes");
                    }
                    return in.readNBytes(cl);
                } catch (NumberFormatException ignored) {}
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                body.write(buf, 0, n);
                if (body.size() > maxBytes) {
                    throw new IllegalStateException("OpenAPI specification exceeds maximum allowed size of " + maxBytes + " bytes");
                }
            }
            return body.toByteArray();
        }
    }

    private String attemptAutoResolveSpec(String originalUrl) {
        try {
            URI uri = URI.create(originalUrl);
            String base = uri.getScheme() + "://" + uri.getAuthority();
            String[] candidatePaths = {"/openapi.json", "/v3/api-docs", "/v2/swagger.json", "/swagger.json"};
            for (String path : candidatePaths) {
                try {
                    String candidateUrl = base + path;
                    SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(candidateUrl);
                    HttpResponseData resp = executePinnedGet(target, 5);
                    if (resp.statusCode == 200) {
                        String res = new String(resp.body, StandardCharsets.UTF_8);
                        if (!res.trim().startsWith("<") && (res.contains("\"openapi\"") || res.contains("\"swagger\""))) {
                            return res;
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
