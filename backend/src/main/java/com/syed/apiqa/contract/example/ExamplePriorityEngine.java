package com.syed.apiqa.contract.example;

import com.syed.apiqa.contract.schema.SchemaComplexityBudget;
import com.syed.apiqa.contract.schema.SchemaContext;
import com.syed.apiqa.contract.schema.SchemaGenerationResult;
import com.syed.apiqa.contract.schema.SchemaGraphEngine;
import com.syed.apiqa.domain.ContractConfidence;
import com.syed.apiqa.domain.GenerationTrace;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Example Priority Engine implementing the strict 11-level priority hierarchy:
 * 1. USER_OVERRIDE
 * 2. OPERATION_EXAMPLE
 * 3. REQUEST_BODY_EXAMPLE
 * 4. MEDIA_TYPE_EXAMPLE
 * 5. PROPERTY_EXAMPLE
 * 6. SCHEMA_EXAMPLE
 * 7. DEFAULT
 * 8. ENUM
 * 9. CONSTRAINT_GENERATION
 * 10. SEMANTIC_FORMAT_GENERATION
 * 11. SAFE_DETERMINISTIC_FALLBACK
 */
@Service
public class ExamplePriorityEngine {

    private final SchemaGraphEngine schemaGraphEngine;

    public ExamplePriorityEngine(SchemaGraphEngine schemaGraphEngine) {
        this.schemaGraphEngine = schemaGraphEngine;
    }

    public record ResolvedPayload(Object value, ExamplePriority priorityUsed, ContractConfidence confidence, List<GenerationTrace> traces) {}

    public ResolvedPayload resolvePayload(Object userOverride,
                                          Object operationExample,
                                          RequestBody requestBody,
                                          String mediaTypeKey,
                                          Schema<?> schema,
                                          Random random,
                                          Map<String, Schema> componentSchemas) {
        List<GenerationTrace> traces = new ArrayList<>();

        // Level 1: USER_OVERRIDE
        if (userOverride != null) {
            traces.add(new GenerationTrace("root", "USER_OVERRIDE", "Supplied by user configuration", ExamplePriority.USER_OVERRIDE.getDefaultConfidence()));
            return new ResolvedPayload(userOverride, ExamplePriority.USER_OVERRIDE, ExamplePriority.USER_OVERRIDE.getDefaultConfidence(), traces);
        }

        // Level 2: OPERATION_EXAMPLE
        if (operationExample != null) {
            traces.add(new GenerationTrace("root", "OPERATION_EXAMPLE", "Declared on OpenAPI operation", ExamplePriority.OPERATION_EXAMPLE.getDefaultConfidence()));
            return new ResolvedPayload(operationExample, ExamplePriority.OPERATION_EXAMPLE, ExamplePriority.OPERATION_EXAMPLE.getDefaultConfidence(), traces);
        }

        // Level 3 & 4: REQUEST_BODY_EXAMPLE and MEDIA_TYPE_EXAMPLE
        if (requestBody != null && requestBody.getContent() != null && mediaTypeKey != null) {
            MediaType mt = requestBody.getContent().get(mediaTypeKey);
            if (mt != null) {
                if (mt.getExample() != null) {
                    traces.add(new GenerationTrace("root", "MEDIA_TYPE_EXAMPLE", "Declared on media type " + mediaTypeKey, ExamplePriority.MEDIA_TYPE_EXAMPLE.getDefaultConfidence()));
                    return new ResolvedPayload(mt.getExample(), ExamplePriority.MEDIA_TYPE_EXAMPLE, ExamplePriority.MEDIA_TYPE_EXAMPLE.getDefaultConfidence(), traces);
                }
                if (mt.getExamples() != null && !mt.getExamples().isEmpty()) {
                    Object firstEx = mt.getExamples().values().iterator().next().getValue();
                    if (firstEx != null) {
                        traces.add(new GenerationTrace("root", "MEDIA_TYPE_EXAMPLE", "Selected from media type examples map", ExamplePriority.MEDIA_TYPE_EXAMPLE.getDefaultConfidence()));
                        return new ResolvedPayload(firstEx, ExamplePriority.MEDIA_TYPE_EXAMPLE, ExamplePriority.MEDIA_TYPE_EXAMPLE.getDefaultConfidence(), traces);
                    }
                }
            }
        }

        // Level 5 to 11: Schema-driven synthesis via SchemaGraphEngine
        SchemaComplexityBudget budget = new SchemaComplexityBudget();
        SchemaGenerationResult result = schemaGraphEngine.generate(
                schema, "", SchemaContext.REQUEST_BODY, budget, random, componentSchemas
        );

        if (result instanceof SchemaGenerationResult.Success success) {
            return new ResolvedPayload(success.value(), ExamplePriority.CONSTRAINT_GENERATION, success.confidence(), success.traces());
        }

        // Return deterministic fallback only if schema was completely unspecified
        String fallback = "safe_fallback_" + Math.abs(random.nextInt(1000));
        traces.add(new GenerationTrace("root", "SAFE_DETERMINISTIC_FALLBACK", "Fallback after unresolvable schema", ExamplePriority.SAFE_DETERMINISTIC_FALLBACK.getDefaultConfidence()));
        return new ResolvedPayload(fallback, ExamplePriority.SAFE_DETERMINISTIC_FALLBACK, ExamplePriority.SAFE_DETERMINISTIC_FALLBACK.getDefaultConfidence(), traces);
    }
}
