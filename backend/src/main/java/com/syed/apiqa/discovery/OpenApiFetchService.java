package com.syed.apiqa.discovery;

import com.syed.apiqa.safety.SsrfProtectionGuard;
import com.syed.apiqa.safety.SsrfProtectionGuard.ValidatedTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real OpenAPI / Swagger specification fetcher & intelligent discovery engine.
 * Deterministically resolves documentation/UI URLs (ReDoc, Swagger UI, /docs, /api/docs)
 * into OpenAPI JSON/YAML specifications without LLMs, without external AI, and without hardcoding.
 * Enforces strict pre-connection SSRF checks, DNS rebinding / IP pinning defense,
 * redirects safety, connection/read timeouts, and response body size limits on ALL targets.
 */
@Service
public class OpenApiFetchService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiFetchService.class);

    private final SsrfProtectionGuard ssrfGuard;

    @Value("${syed.safety.default-timeout-seconds:15}")
    private int timeoutSeconds = 15;

    @Value("${syed.safety.max-response-size-bytes:2097152}")
    private int maxSizeBytes = 2097152;

    private static final List<String> COMMON_FALLBACK_PATHS = List.of(
            "/v3/api-docs",
            "/openapi.json",
            "/openapi.yaml",
            "/swagger.json",
            "/swagger.yaml",
            "/api/openapi.json",
            "/api/openapi.yaml",
            "/api/swagger.json",
            "/api/swagger.yaml",
            "/api/v3/api-docs"
    );

    public OpenApiFetchService(SsrfProtectionGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    public String fetchSpecification(String specUrl) {
        return fetchSpecificationResult(specUrl).getContent();
    }

    public OpenApiDiscoveryResult fetchSpecificationResult(String specUrl) {
        if (specUrl == null || specUrl.isBlank()) {
            throw new IllegalArgumentException("Specification URL cannot be empty");
        }

        String currentUrl = specUrl.trim();
        HttpResponseData initialResponse = fetchUrlSafely(currentUrl, 5);

        String content = new String(initialResponse.body, StandardCharsets.UTF_8);

        if (isValidOpenApiSpec(content)) {
            log.info("Direct OpenAPI specification discovered at {}", currentUrl);
            return new OpenApiDiscoveryResult(specUrl, currentUrl, OpenApiDiscoveryResult.DiscoveryMethod.DIRECT_SPEC, content, List.of(currentUrl));
        }

        // Target returned HTML or non-spec content -> Execute deterministic discovery
        log.info("Target URL {} returned HTML/non-spec content. Commencing deterministic OpenAPI discovery...", currentUrl);
        return discoverSpecFromHtml(specUrl, currentUrl, content);
    }

    private OpenApiDiscoveryResult discoverSpecFromHtml(String originalUrl, String htmlUrl, String htmlContent) {
        List<String> htmlExtractedCandidates = extractCandidatesFromHtml(htmlUrl, htmlContent);
        List<String> fallbackCandidates = buildFallbackCandidates(htmlUrl);

        List<String> attemptedUrls = new ArrayList<>();
        attemptedUrls.add(htmlUrl);

        // Deduplicate and prioritize same-origin candidates
        List<String> candidateUrls = prioritizeCandidates(htmlUrl, htmlExtractedCandidates, fallbackCandidates);

        for (String candidateUrl : candidateUrls) {
            if (attemptedUrls.contains(candidateUrl)) {
                continue;
            }
            attemptedUrls.add(candidateUrl);

            try {
                log.debug("Checking OpenAPI spec candidate URL: {}", candidateUrl);
                // Security check on candidate URL - must pass full SSRF validation
                HttpResponseData resp = fetchUrlSafely(candidateUrl, 3);
                if (resp.statusCode >= 200 && resp.statusCode < 300) {
                    String specText = new String(resp.body, StandardCharsets.UTF_8);
                    if (isValidOpenApiSpec(specText)) {
                        OpenApiDiscoveryResult.DiscoveryMethod method = htmlExtractedCandidates.contains(candidateUrl)
                                ? OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY
                                : OpenApiDiscoveryResult.DiscoveryMethod.COMMON_PATH_FALLBACK;

                        log.info("OpenAPI specification successfully resolved via {}! Input: {}, Spec: {}",
                                method, originalUrl, candidateUrl);

                        return new OpenApiDiscoveryResult(originalUrl, candidateUrl, method, specText, attemptedUrls);
                    }
                }
            } catch (Exception e) {
                log.debug("Candidate spec URL {} failed: {}", candidateUrl, e.getMessage());
            }
        }

        // Build actionable failure message (Phase 10)
        StringBuilder sb = new StringBuilder();
        sb.append("The supplied URL is an HTML API documentation page.\n\n");
        sb.append("Automatic specification discovery was attempted but no valid OpenAPI specification was found.\n\n");
        sb.append("Input:\n").append(originalUrl).append("\n\n");
        sb.append("Candidates checked:\n");
        for (String url : attemptedUrls) {
            sb.append("- ").append(url).append("\n");
        }
        sb.append("\nProvide the direct specification URL if the documentation page does not expose one.");

        throw new IllegalArgumentException(sb.toString());
    }

    private HttpResponseData fetchUrlSafely(String targetUrl, int maxRedirects) {
        int redirects = 0;
        String current = targetUrl;

        while (redirects < maxRedirects) {
            SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(current);
            HttpResponseData resp;
            try {
                resp = executePinnedGet(target, timeoutSeconds);
            } catch (Exception e) {
                throw new RuntimeException("Error fetching URL " + current + ": " + e.getMessage(), e);
            }

            if (resp.statusCode >= 300 && resp.statusCode < 400) {
                String redirectLocation = resp.headers.get("location");
                if (redirectLocation == null || redirectLocation.isBlank()) {
                    throw new IllegalStateException("HTTP redirect received without Location header from " + current);
                }
                URI redirectedUri = URI.create(current).resolve(redirectLocation);
                current = redirectedUri.toString();
                redirects++;
                continue;
            }

            if (resp.statusCode < 200 || resp.statusCode >= 300) {
                throw new IllegalStateException("Failed HTTP request to " + current + ". Status: " + resp.statusCode);
            }

            return resp;
        }

        throw new IllegalStateException("Too many redirects encountered while fetching " + targetUrl);
    }

    private List<String> extractCandidatesFromHtml(String baseUrl, String htmlContent) {
        List<String> candidates = new ArrayList<>();
        if (htmlContent == null || htmlContent.isBlank()) {
            return candidates;
        }

        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (Exception e) {
            return candidates;
        }

        // 1. ReDoc spec-url attribute / config
        Pattern redocPattern = Pattern.compile("(?:spec-url|specUrl)\\s*[:=]\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher m1 = redocPattern.matcher(htmlContent);
        while (m1.find()) {
            addResolvedCandidate(candidates, baseUri, m1.group(1));
        }

        // 2. Swagger UI url / urls configuration
        Pattern swaggerUrlPattern = Pattern.compile("(?<![a-zA-Z0-9_-])url\\s*:\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher m2 = swaggerUrlPattern.matcher(htmlContent);
        while (m2.find()) {
            addResolvedCandidate(candidates, baseUri, m2.group(1));
        }

        Pattern swaggerUrlsPattern = Pattern.compile("urls\\s*:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
        Matcher m3 = swaggerUrlsPattern.matcher(htmlContent);
        if (m3.find()) {
            String urlsContent = m3.group(1);
            Matcher urlInListMatcher = Pattern.compile("url\\s*:\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(urlsContent);
            while (urlInListMatcher.find()) {
                addResolvedCandidate(candidates, baseUri, urlInListMatcher.group(1));
            }
        }

        // 3. HTML href links to common spec extensions/names
        Pattern anchorPattern = Pattern.compile("<a\\s+[^>]*href=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher m4 = anchorPattern.matcher(htmlContent);
        while (m4.find()) {
            String href = m4.group(1).trim();
            String lower = href.toLowerCase();
            if (lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml") ||
                lower.contains("api-docs") || lower.contains("openapi") || lower.contains("swagger")) {
                addResolvedCandidate(candidates, baseUri, href);
            }
        }

        return candidates;
    }

    private void addResolvedCandidate(List<String> list, URI baseUri, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return;
        String trimmed = relativeOrAbsolute.trim();
        // Ignore Javascript pseudo-protocols or placeholders
        if (trimmed.startsWith("javascript:") || trimmed.startsWith("#") || trimmed.contains("{")) return;

        try {
            URI resolved = baseUri.resolve(trimmed);
            String urlStr = resolved.toString();
            if ((resolved.getScheme().equalsIgnoreCase("http") || resolved.getScheme().equalsIgnoreCase("https")) &&
                !list.contains(urlStr)) {
                list.add(urlStr);
            }
        } catch (Exception ignored) {}
    }

    private List<String> buildFallbackCandidates(String baseUrl) {
        List<String> list = new ArrayList<>();
        try {
            URI baseUri = URI.create(baseUrl);
            String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();

            // 1. Candidates relative to current page path (e.g. /api/docs -> /api/openapi.json)
            addResolvedCandidate(list, baseUri, "openapi.json");
            addResolvedCandidate(list, baseUri, "openapi.yaml");
            addResolvedCandidate(list, baseUri, "swagger.json");
            addResolvedCandidate(list, baseUri, "swagger.yaml");
            addResolvedCandidate(list, baseUri, "v3/api-docs");

            // 2. Candidates under current path parent directory
            String path = baseUri.getPath();
            if (path != null && path.length() > 1) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    String parentPath = path.substring(0, lastSlash);
                    for (String fc : COMMON_FALLBACK_PATHS) {
                        String candidate = origin + parentPath + fc;
                        if (!list.contains(candidate)) {
                            list.add(candidate);
                        }
                    }
                }
            }

            // 3. Origin-root fallback candidates
            for (String p : COMMON_FALLBACK_PATHS) {
                String candidate = origin + p;
                if (!list.contains(candidate)) {
                    list.add(candidate);
                }
            }

        } catch (Exception ignored) {}
        return list;
    }

    private List<String> prioritizeCandidates(String baseUrl, List<String> htmlCandidates, List<String> fallbackCandidates) {
        List<String> sameOrigin = new ArrayList<>();
        List<String> externalOrigin = new ArrayList<>();

        String baseOrigin = "";
        try {
            URI uri = URI.create(baseUrl);
            baseOrigin = (uri.getScheme() + "://" + uri.getAuthority()).toLowerCase();
        } catch (Exception ignored) {}

        List<String> combined = new ArrayList<>(htmlCandidates);
        for (String fc : fallbackCandidates) {
            if (!combined.contains(fc)) {
                combined.add(fc);
            }
        }

        for (String candidate : combined) {
            try {
                URI candUri = URI.create(candidate);
                String candOrigin = (candUri.getScheme() + "://" + candUri.getAuthority()).toLowerCase();
                if (candOrigin.equals(baseOrigin)) {
                    sameOrigin.add(candidate);
                } else {
                    externalOrigin.add(candidate);
                }
            } catch (Exception ignored) {}
        }

        List<String> result = new ArrayList<>(sameOrigin);
        result.addAll(externalOrigin);
        return result;
    }

    public boolean isValidOpenApiSpec(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String trimmed = content.trim();

        // HTML pages must NEVER be treated as OpenAPI specifications
        if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || trimmed.startsWith("<head") || trimmed.startsWith("<body")) {
            return false;
        }

        // Structural validation for JSON / YAML OpenAPI 3.x and Swagger 2.x
        boolean hasOpenApi3 = trimmed.contains("\"openapi\"") || trimmed.contains("'openapi'") || Pattern.compile("(?m)^openapi\\s*:").matcher(trimmed).find();
        boolean hasSwagger2 = trimmed.contains("\"swagger\"") || trimmed.contains("'swagger'") || Pattern.compile("(?m)^swagger\\s*:").matcher(trimmed).find();
        boolean hasPaths = trimmed.contains("\"paths\"") || trimmed.contains("'paths'") || Pattern.compile("(?m)^paths\\s*:").matcher(trimmed).find();

        return (hasOpenApi3 || hasSwagger2) && hasPaths;
    }

    private record HttpResponseData(int statusCode, Map<String, String> headers, byte[] body) {}

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
            params.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(target.originalHost())));
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

            Map<String, String> headers = new LinkedHashMap<>();
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

    private static byte[] readHttpBody(InputStream in, Map<String, String> headers, int maxBytes) throws java.io.IOException {
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
}
