package com.syed.apiqa.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.TestRun;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

/**
 * Parses OpenAPI 3.x and Swagger 2.x specifications into normalized ApiEndpoint domain entities.
 * Resolves references safely and extracts schema parameters and contracts.
 */
@Service
public class OpenApiParserService {

    private final ObjectMapper objectMapper;

    public OpenApiParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static class DiscoveryResult {
        private final OpenAPI openAPI;
        private final String resolvedBaseUrl;
        private final List<ApiEndpoint> endpoints;

        public DiscoveryResult(OpenAPI openAPI, String resolvedBaseUrl, List<ApiEndpoint> endpoints) {
            this.openAPI = openAPI;
            this.resolvedBaseUrl = resolvedBaseUrl;
            this.endpoints = endpoints;
        }

        public OpenAPI getOpenAPI() { return openAPI; }
        public String getResolvedBaseUrl() { return resolvedBaseUrl; }
        public List<ApiEndpoint> getEndpoints() { return endpoints; }
    }

    public DiscoveryResult parse(String specContent, String originalSpecUrl, TestRun testRun) {
        ParseOptions options = new ParseOptions();
        options.setResolve(false); // Prevents external network requests during parsing (SSRF protection)
        options.setResolveFully(false); // Prevents stack overflow on circular references

        SwaggerParseResult parseResult = new io.swagger.parser.OpenAPIParser().readContents(specContent, null, options);

        if (parseResult == null || parseResult.getOpenAPI() == null) {
            List<String> messages = parseResult != null ? parseResult.getMessages() : Collections.emptyList();
            throw new IllegalArgumentException("Failed to parse OpenAPI/Swagger content. Errors: " + String.join("; ", messages));
        }

        OpenAPI openAPI = parseResult.getOpenAPI();
        String resolvedBaseUrl = determineBaseUrl(openAPI, originalSpecUrl);
        List<ApiEndpoint> endpoints = new ArrayList<>();

        if (openAPI.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();

                List<Parameter> commonParams = pathItem.getParameters() != null ? pathItem.getParameters() : Collections.emptyList();

                extractOperation(endpoints, testRun, "GET", path, pathItem.getGet(), commonParams);
                extractOperation(endpoints, testRun, "POST", path, pathItem.getPost(), commonParams);
                extractOperation(endpoints, testRun, "PUT", path, pathItem.getPut(), commonParams);
                extractOperation(endpoints, testRun, "PATCH", path, pathItem.getPatch(), commonParams);
                extractOperation(endpoints, testRun, "DELETE", path, pathItem.getDelete(), commonParams);
                extractOperation(endpoints, testRun, "HEAD", path, pathItem.getHead(), commonParams);
                extractOperation(endpoints, testRun, "OPTIONS", path, pathItem.getOptions(), commonParams);
            }
        }

        return new DiscoveryResult(openAPI, resolvedBaseUrl, endpoints);
    }

    private void extractOperation(List<ApiEndpoint> endpoints, TestRun testRun, String method, String path,
                                  Operation operation, List<Parameter> commonParams) {
        if (operation == null) return;

        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(UUID.randomUUID().toString());
        endpoint.setTestRun(testRun);
        endpoint.setMethod(method);
        endpoint.setPath(path);
        endpoint.setOperationId(operation.getOperationId() != null ? operation.getOperationId() : method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"));
        endpoint.setSummary(operation.getSummary());
        endpoint.setDescription(operation.getDescription());

        // Tags
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            try {
                endpoint.setTags(objectMapper.writeValueAsString(operation.getTags()));
            } catch (Exception ignored) {}
        }

        // Parameters (merge path-level common parameters with operation-specific parameters)
        List<Parameter> allParams = new ArrayList<>(commonParams);
        if (operation.getParameters() != null) {
            allParams.addAll(operation.getParameters());
        }
        if (!allParams.isEmpty()) {
            try {
                endpoint.setParameters(objectMapper.writeValueAsString(allParams));
            } catch (Exception ignored) {}
        }

        // Request Body Schema
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            MediaType jsonMediaType = operation.getRequestBody().getContent().get("application/json");
            if (jsonMediaType == null) {
                // Fallback to first available media type
                Iterator<MediaType> it = operation.getRequestBody().getContent().values().iterator();
                if (it.hasNext()) jsonMediaType = it.next();
            }
            if (jsonMediaType != null && jsonMediaType.getSchema() != null) {
                try {
                    endpoint.setRequestBodySchema(objectMapper.writeValueAsString(jsonMediaType.getSchema()));
                } catch (Exception ignored) {}
            }
        }

        // Response Schemas
        if (operation.getResponses() != null && !operation.getResponses().isEmpty()) {
            Map<String, Object> responseMap = new LinkedHashMap<>();
            for (Map.Entry<String, ApiResponse> respEntry : operation.getResponses().entrySet()) {
                String statusCode = respEntry.getKey();
                ApiResponse resp = respEntry.getValue();
                Map<String, Object> respData = new HashMap<>();
                respData.put("description", resp.getDescription());
                if (resp.getContent() != null && resp.getContent().get("application/json") != null) {
                    Schema<?> s = resp.getContent().get("application/json").getSchema();
                    respData.put("schema", s);
                }
                if (resp.getHeaders() != null && !resp.getHeaders().isEmpty()) {
                    respData.put("headers", resp.getHeaders().keySet());
                }
                responseMap.put(statusCode, respData);
            }
            try {
                endpoint.setResponseSchemas(objectMapper.writeValueAsString(responseMap));
            } catch (Exception ignored) {}
        }

        // Security requirements
        if (operation.getSecurity() != null) {
            try {
                endpoint.setSecurityRequirements(objectMapper.writeValueAsString(operation.getSecurity()));
            } catch (Exception ignored) {}
        }

        endpoints.add(endpoint);
    }

    private String determineBaseUrl(OpenAPI openAPI, String originalSpecUrl) {
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            for (Server server : openAPI.getServers()) {
                String url = server.getUrl();
                if (url != null && !url.isBlank()) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return stripTrailingSlash(url);
                    } else if (url.startsWith("/")) {
                        // Relative path on the original spec's host
                        try {
                            URI specUri = URI.create(originalSpecUrl);
                            return specUri.getScheme() + "://" + specUri.getAuthority() + stripTrailingSlash(url);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Fallback to origin host of the OpenAPI URL
        try {
            URI specUri = URI.create(originalSpecUrl);
            return specUri.getScheme() + "://" + specUri.getAuthority();
        } catch (Exception e) {
            return "http://localhost:8080";
        }
    }

    private String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
