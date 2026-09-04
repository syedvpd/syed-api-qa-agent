package com.syed.apiqa.discovery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAPI / Docs / ReDoc Intelligent Discovery Test Suite")
public class OpenApiDiscoveryServiceTest {

    private static HttpServer mockServer;
    private static int serverPort;
    private static String baseUrl;

    private SsrfProtectionGuard ssrfGuard;
    private OpenApiFetchService fetchService;

    private static final String VALID_OPENAPI_3_JSON = """
            {
              "openapi": "3.0.1",
              "info": { "title": "Test API", "version": "1.0.0" },
              "paths": {
                "/users": {
                  "get": {
                    "summary": "Get users",
                    "responses": { "200": { "description": "OK" } }
                  }
                }
              }
            }
            """;

    private static final String VALID_SWAGGER_2_JSON = """
            {
              "swagger": "2.0",
              "info": { "title": "Legacy API", "version": "2.0.0" },
              "paths": {
                "/legacy": {
                  "get": {
                    "summary": "Get legacy data",
                    "responses": { "200": { "description": "OK" } }
                  }
                }
              }
            }
            """;

    private static final String VALID_OPENAPI_3_YAML = """
            openapi: 3.0.0
            info:
              title: YAML API
              version: 1.0.0
            paths:
              /items:
                get:
                  summary: Get items
                  responses:
                    '200':
                      description: OK
            """;

    @BeforeAll
    static void startServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPort = mockServer.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + serverPort;

        mockServer.createContext("/direct/openapi.json", new StringHandler(VALID_OPENAPI_3_JSON, "application/json"));
        mockServer.createContext("/direct/swagger.json", new StringHandler(VALID_SWAGGER_2_JSON, "application/json"));
        mockServer.createContext("/direct/openapi.yaml", new StringHandler(VALID_OPENAPI_3_YAML, "application/yaml"));

        // ReDoc scenarios
        mockServer.createContext("/redoc-rel/index.html", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"/direct/openapi.json\"></redoc></body></html>", "text/html"));
        mockServer.createContext("/redoc-abs/index.html", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"" + baseUrl + "/direct/openapi.json\"></redoc></body></html>", "text/html"));

        // Swagger UI scenarios
        mockServer.createContext("/swagger-ui/index.html", new StringHandler(
                "<!DOCTYPE html><html><script>SwaggerUIBundle({url: '/direct/swagger.json'});</script></html>", "text/html"));
        mockServer.createContext("/swagger-ui-urls/index.html", new StringHandler(
                "<!DOCTYPE html><html><script>const config = { urls: [{url: '/direct/openapi.json', name: 'v1'}] };</script></html>", "text/html"));

        // Fallback scenarios
        mockServer.createContext("/docs", new StringHandler(
                "<!DOCTYPE html><html><body><h1>API Docs</h1></body></html>", "text/html"));
        mockServer.createContext("/v3/api-docs", new StringHandler(VALID_OPENAPI_3_JSON, "application/json"));

        mockServer.createContext("/api/docs", new StringHandler(
                "<!DOCTYPE html><html><body><h1>Skyline Docs</h1></body></html>", "text/html"));
        mockServer.createContext("/api/openapi.json", new StringHandler(VALID_OPENAPI_3_JSON, "application/json"));

        // Skyline exact bug reproduction (/api/redoc/ -> <redoc spec-url="/openapi.json"> -> /openapi.json)
        mockServer.createContext("/api/redoc/", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"/openapi.json\"></redoc></body></html>", "text/html"));
        mockServer.createContext("/openapi.json", new StringHandler(VALID_OPENAPI_3_JSON, "application/json"));

        // Failure scenarios
        mockServer.createContext("/empty-html", new StringHandler("<!DOCTYPE html><html><body>No spec anywhere</body></html>", "text/html"));
        mockServer.createContext("/invalid-json", new StringHandler("{ \"foo\": \"bar\" }", "application/json"));
        mockServer.createContext("/invalid-yaml", new StringHandler("foo:\n  bar: baz", "application/yaml"));

        // Security / SSRF scenarios inside HTML
        mockServer.createContext("/security/metadata", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"http://169.254.169.254/latest/meta-data\"></redoc></body></html>", "text/html"));
        mockServer.createContext("/security/loopback", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"http://127.0.0.1:9999/spec.json\"></redoc></body></html>", "text/html"));
        mockServer.createContext("/security/private-ip", new StringHandler(
                "<!DOCTYPE html><html><body><redoc spec-url=\"http://10.0.0.1/openapi.json\"></redoc></body></html>", "text/html"));

        mockServer.setExecutor(Executors.newCachedThreadPool());
        mockServer.start();

        // Separate mock server where NO valid spec candidate exists
        noSpecServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        noSpecBaseUrl = "http://127.0.0.1:" + noSpecServer.getAddress().getPort();
        noSpecServer.createContext("/no-spec-docs", new StringHandler("<!DOCTYPE html><html><body>No spec here</body></html>", "text/html"));
        noSpecServer.setExecutor(Executors.newCachedThreadPool());
        noSpecServer.start();
    }

    private static HttpServer noSpecServer;
    private static String noSpecBaseUrl;

    @AfterAll
    static void stopServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
        if (noSpecServer != null) {
            noSpecServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfProtectionGuard();
        ssrfGuard.setSsrfProtectionEnabled(true);
        ssrfGuard.setAllowLocalTargets(true); // Allow localhost for local mock server tests
        fetchService = new OpenApiFetchService(ssrfGuard);
    }

    // -------------------------------------------------------------------------
    // DIRECT SPECIFICATION TESTS (1-4)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Direct OpenAPI 3.x JSON")
    void testDirectOpenApi3Json() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/direct/openapi.json");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.DIRECT_SPEC, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/direct/openapi.json", res.getDiscoveredSpecUrl());
        assertTrue(res.getContent().contains("\"openapi\": \"3.0.1\""));
    }

    @Test
    @DisplayName("2. Direct OpenAPI 3.x YAML")
    void testDirectOpenApi3Yaml() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/direct/openapi.yaml");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.DIRECT_SPEC, res.getDiscoveryMethod());
        assertTrue(res.getContent().contains("openapi: 3.0.0"));
    }

    @Test
    @DisplayName("3. Direct Swagger 2.0 JSON")
    void testDirectSwagger2Json() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/direct/swagger.json");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.DIRECT_SPEC, res.getDiscoveryMethod());
        assertTrue(res.getContent().contains("\"swagger\": \"2.0\""));
    }

    // -------------------------------------------------------------------------
    // REDOC HTML DISCOVERY TESTS (5-6)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. ReDoc HTML with relative spec URL")
    void testReDocRelativeSpecUrl() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/redoc-rel/index.html");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/direct/openapi.json", res.getDiscoveredSpecUrl());
        assertTrue(res.getContent().contains("\"openapi\": \"3.0.1\""));
    }

    @Test
    @DisplayName("6. ReDoc HTML with absolute spec URL")
    void testReDocAbsoluteSpecUrl() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/redoc-abs/index.html");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/direct/openapi.json", res.getDiscoveredSpecUrl());
    }

    // -------------------------------------------------------------------------
    // SWAGGER UI HTML DISCOVERY TESTS (7-8)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Swagger UI HTML with url parameter")
    void testSwaggerUiUrl() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/swagger-ui/index.html");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/direct/swagger.json", res.getDiscoveredSpecUrl());
    }

    @Test
    @DisplayName("8. Swagger UI HTML with urls array parameter")
    void testSwaggerUiUrlsArray() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/swagger-ui-urls/index.html");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/direct/openapi.json", res.getDiscoveredSpecUrl());
    }

    // -------------------------------------------------------------------------
    // FALLBACK CANDIDATE DISCOVERY TESTS (9-10)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. /docs returning HTML falling back to valid specification candidate")
    void testFallbackDocsToV3ApiDocs() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/docs");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.COMMON_PATH_FALLBACK, res.getDiscoveryMethod());
        assertTrue(res.getDiscoveredSpecUrl().endsWith("/v3/api-docs") || res.getDiscoveredSpecUrl().endsWith("/openapi.json"));
    }

    @Test
    @DisplayName("10. /api/docs returning HTML falling back to /api/openapi.json")
    void testFallbackApiDocsToApiOpenApiJson() {
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/api/docs");
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.COMMON_PATH_FALLBACK, res.getDiscoveryMethod());
        assertEquals(baseUrl + "/api/openapi.json", res.getDiscoveredSpecUrl());
    }

    // -------------------------------------------------------------------------
    // FAILURE SCENARIO TESTS (11-16)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. HTML page with no spec reference and no available fallback")
    void testFailureNoSpecInHtml() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fetchService.fetchSpecificationResult(noSpecBaseUrl + "/no-spec-docs"));
        assertTrue(ex.getMessage().contains("Automatic specification discovery was attempted but no valid OpenAPI specification was found"));
        assertTrue(ex.getMessage().contains("Candidates checked:"));
    }

    @Test
    @DisplayName("12 & 13. Content validation rejects non-OpenAPI JSON/YAML")
    void testContentValidation() {
        assertFalse(fetchService.isValidOpenApiSpec("{ \"foo\": \"bar\" }"));
        assertFalse(fetchService.isValidOpenApiSpec("foo:\n  bar: baz"));
        assertFalse(fetchService.isValidOpenApiSpec("<!DOCTYPE html><html><head><title>Test</title></head></html>"));
        assertTrue(fetchService.isValidOpenApiSpec(VALID_OPENAPI_3_JSON));
        assertTrue(fetchService.isValidOpenApiSpec(VALID_SWAGGER_2_JSON));
        assertTrue(fetchService.isValidOpenApiSpec(VALID_OPENAPI_3_YAML));
    }

    // -------------------------------------------------------------------------
    // SECURITY & SSRF PROTECTION TESTS (17-23)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("17 & 18. Security Guard blocks localhost/127.0.0.1 when allowLocal=false")
    void testSecurityBlocksLocalhostInProductionMode() {
        ssrfGuard.setAllowLocalTargets(false);
        assertThrows(SecurityException.class, () ->
                fetchService.fetchSpecificationResult("http://127.0.0.1:" + serverPort + "/direct/openapi.json"));
    }

    @Test
    @DisplayName("20. Security Guard blocks cloud metadata endpoint in HTML discovery")
    void testSecurityBlocksCloudMetadataInHtml() {
        ssrfGuard.setAllowLocalTargets(false);
        // HTML points to 169.254.169.254; when resolved, SSRF guard MUST block it
        assertThrows(SecurityException.class, () ->
                fetchService.fetchSpecificationResult(baseUrl + "/security/metadata"));
    }

    @Test
    @DisplayName("19 & 23. Security Guard enforces strict validation on external candidate URLs")
    void testSecurityEnforcesSsrfOnExternalUrls() {
        ssrfGuard.setAllowLocalTargets(false);
        assertThrows(SecurityException.class, () ->
                fetchService.fetchSpecificationResult(baseUrl + "/security/private-ip"));
    }

    // -------------------------------------------------------------------------
    // CONCURRENCY & ISOLATION TESTS (24-25)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("24 & 25. Concurrent discovery calls maintain complete isolation")
    void testConcurrentDiscoveryIsolation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);

        List<Future<OpenApiDiscoveryResult>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                latch.countDown();
                latch.await();
                String target = (idx % 2 == 0) ? baseUrl + "/redoc-rel/index.html" : baseUrl + "/swagger-ui/index.html";
                return fetchService.fetchSpecificationResult(target);
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<OpenApiDiscoveryResult> f : futures) {
            OpenApiDiscoveryResult res = f.get();
            assertNotNull(res);
            assertNotNull(res.getContent());
            assertTrue(res.getContent().length() > 0);
        }
    }

    // -------------------------------------------------------------------------
    // PHASE 13 — SKYLINE EXACT BUG REGRESSION TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 13: Skyline /api/redoc/ exact failure regression test")
    void testSkylineRedocExactFailureRegression() {
        // User inputs: http://127.0.0.1:port/api/redoc/
        // Server returns ReDoc HTML with spec-url="/openapi.json"
        // /openapi.json returns valid OpenAPI 3
        OpenApiDiscoveryResult res = fetchService.fetchSpecificationResult(baseUrl + "/api/redoc/");

        assertEquals(baseUrl + "/api/redoc/", res.getOriginalUrl());
        assertEquals(baseUrl + "/openapi.json", res.getDiscoveredSpecUrl());
        assertEquals(OpenApiDiscoveryResult.DiscoveryMethod.HTML_REDISCOVERY, res.getDiscoveryMethod());

        // Parse with OpenApiParserService to ensure full end-to-end compatibility
        OpenApiParserService parser = new OpenApiParserService(new com.fasterxml.jackson.databind.ObjectMapper());
        OpenApiParserService.DiscoveryResult parseResult = parser.parse(res.getContent(), res.getDiscoveredSpecUrl(), null);

        assertNotNull(parseResult);
        assertNotNull(parseResult.getOpenAPI());
        assertTrue(parseResult.getEndpoints().size() > 0, "Routes discovered must be greater than 0");
    }

    private static class StringHandler implements HttpHandler {
        private final String body;
        private final String contentType;

        StringHandler(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
