package com.syed.apiqa.discovery;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal OpenAPI Schema and Model Registry.
 * Holds active OpenAPI definitions and component schemas per testRunId,
 * enabling safe dereferencing and schema-aware generation across test planning,
 * execution, pre-request validation, and authentication bootstrap.
 */
@Service
public class OpenApiSchemaRegistry {

    private final Map<String, OpenAPI> openApiByRunId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Schema>> schemasByRunId = new ConcurrentHashMap<>();

    public void registerOpenApi(String testRunId, OpenAPI openAPI) {
        if (testRunId != null && openAPI != null) {
            openApiByRunId.put(testRunId, openAPI);
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                schemasByRunId.put(testRunId, openAPI.getComponents().getSchemas());
            }
        }
    }

    public void registerSchemas(String testRunId, Map<String, Schema> schemas) {
        if (testRunId != null && schemas != null) {
            schemasByRunId.put(testRunId, schemas);
        }
    }

    public OpenAPI getOpenApi(String testRunId) {
        if (testRunId == null) return null;
        return openApiByRunId.get(testRunId);
    }

    public Map<String, Schema> getSchemas(String testRunId) {
        if (testRunId == null) return Collections.emptyMap();
        return schemasByRunId.getOrDefault(testRunId, Collections.emptyMap());
    }

    public void clear(String testRunId) {
        if (testRunId != null) {
            openApiByRunId.remove(testRunId);
            schemasByRunId.remove(testRunId);
        }
    }
}
