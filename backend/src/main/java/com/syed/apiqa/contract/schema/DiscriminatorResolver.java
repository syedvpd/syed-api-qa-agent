package com.syed.apiqa.contract.schema;

import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves polymorphic branch selection via OpenAPI discriminators for oneOf and anyOf schemas.
 */
@Component
public class DiscriminatorResolver {

    public record DiscriminatorMatch(Schema<?> selectedBranch, String propertyName, String discriminatorValue) {}

    public DiscriminatorMatch resolveBranch(Discriminator discriminator, List<Schema> branches, Map<String, Schema> componentSchemas) {
        if (branches == null || branches.isEmpty()) {
            return null;
        }

        if (discriminator == null || discriminator.getPropertyName() == null) {
            // Default to first valid branch if no discriminator declared
            return new DiscriminatorMatch(branches.get(0), null, null);
        }

        String propName = discriminator.getPropertyName();
        Map<String, String> mapping = discriminator.getMapping();

        if (mapping != null && !mapping.isEmpty()) {
            // Check mapping
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String discValue = entry.getKey();
                String targetRef = entry.getValue();

                String refKey = targetRef.substring(targetRef.lastIndexOf('/') + 1);
                if (componentSchemas != null && componentSchemas.containsKey(refKey)) {
                    return new DiscriminatorMatch(componentSchemas.get(refKey), propName, discValue);
                }
            }
        }

        // Fallback to first branch
        return new DiscriminatorMatch(branches.get(0), propName, "default");
    }
}
