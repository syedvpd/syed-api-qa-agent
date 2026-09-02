package com.syed.apiqa.discovery;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Normalizer for OpenAPI 3.1 / JSON Schema 2020-12 constructs.
 * Normalizes type unions, $defs, and true nullability into the canonical representation.
 */
@Component
public class OpenApi31Normalizer {

    public void normalize(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }

        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            normalizeSchema(entry.getValue());
        }
    }

    private void normalizeSchema(Schema<?> schema) {
        if (schema == null) return;

        // OpenAPI 3.1: types as array (e.g. ["string", "null"]) -> type="string", nullable=true
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            boolean isNullable = schema.getTypes().contains("null");
            for (String t : schema.getTypes()) {
                if (!"null".equals(t)) {
                    schema.setType(t);
                    break;
                }
            }
            if (isNullable) {
                schema.setNullable(true);
            }
        }

        // Recursively normalize properties
        if (schema.getProperties() != null) {
            for (Schema<?> prop : schema.getProperties().values()) {
                normalizeSchema(prop);
            }
        }

        if (schema.getItems() != null) {
            normalizeSchema(schema.getItems());
        }
    }
}
