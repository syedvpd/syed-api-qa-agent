package com.syed.apiqa.contract.schema;

import com.syed.apiqa.domain.ContractConfidence;
import com.syed.apiqa.domain.GenerationTrace;
import com.syed.apiqa.safety.SensitiveDataClassifier;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Universal Schema Graph Engine.
 * Traverses complex schema graphs, dereferences local $refs, detects cycles, enforces complexity budgets,
 * contextually projects readOnly/writeOnly properties, and guarantees zero silent scalar fallbacks on object failures.
 */
@Service
public class SchemaGraphEngine {

    private final DiscriminatorResolver discriminatorResolver;
    private final PatternGenerator patternGenerator;
    private final SensitiveDataClassifier sensitiveDataClassifier;

    public SchemaGraphEngine(DiscriminatorResolver discriminatorResolver,
                             PatternGenerator patternGenerator,
                             SensitiveDataClassifier sensitiveDataClassifier) {
        this.discriminatorResolver = discriminatorResolver;
        this.patternGenerator = patternGenerator;
        this.sensitiveDataClassifier = sensitiveDataClassifier;
    }

    public SchemaGenerationResult generate(Schema<?> rootSchema,
                                          String propertyPath,
                                          SchemaContext context,
                                          SchemaComplexityBudget budget,
                                          Random random,
                                          Map<String, Schema> openApiSchemas) {
        Set<String> visitedRefs = new HashSet<>();
        List<GenerationTrace> traces = new ArrayList<>();
        return generateInternal(rootSchema, propertyPath, context, budget, random, openApiSchemas, 0, visitedRefs, traces);
    }

    private SchemaGenerationResult generateInternal(Schema<?> schema,
                                                    String propertyPath,
                                                    SchemaContext context,
                                                    SchemaComplexityBudget budget,
                                                    Random random,
                                                    Map<String, Schema> openApiSchemas,
                                                    int currentDepth,
                                                    Set<String> visitedRefs,
                                                    List<GenerationTrace> traces) {
        if (schema == null) {
            return new SchemaGenerationResult.GenerationFailure(propertyPath, "Null schema provided", "Cannot synthesize data for null schema");
        }

        // Budget depth enforcement
        if (currentDepth > budget.getMaxExpansionDepth()) {
            return new SchemaGenerationResult.ComplexityLimitExceeded(
                    propertyPath, "maxExpansionDepth", budget.getMaxExpansionDepth(),
                    "Recursion depth exceeded safety limit"
            );
        }

        // 1. Dereference $ref safely
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (visitedRefs.contains(ref)) {
                // Cycle broken safely: return null or empty object if depth > 1 to stop loop
                traces.add(new GenerationTrace(propertyPath, "CycleBreaker", "Recursive reference loop broken: " + ref, ContractConfidence.MEDIUM));
                return new SchemaGenerationResult.Success(null, ContractConfidence.MEDIUM, traces);
            }

            visitedRefs.add(ref);
            String refKey = ref.substring(ref.lastIndexOf('/') + 1);
            Schema<?> target = openApiSchemas != null ? openApiSchemas.get(refKey) : null;
            if (target == null) {
                return new SchemaGenerationResult.MissingRequiredReference(ref, "Could not resolve component schema reference in document");
            }
            return generateInternal(target, propertyPath, context, budget, random, openApiSchemas, currentDepth + 1, visitedRefs, traces);
        }

        // 2. ComposedSchema handling (allOf, oneOf, anyOf)
        if (schema instanceof ComposedSchema composed) {
            if (composed.getAllOf() != null && !composed.getAllOf().isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>();
                for (Schema<?> sub : composed.getAllOf()) {
                    SchemaGenerationResult subResult = generateInternal(sub, propertyPath, context, budget, random, openApiSchemas, currentDepth + 1, visitedRefs, traces);
                    if (subResult instanceof SchemaGenerationResult.Success success && success.value() instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            merged.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                }
                traces.add(new GenerationTrace(propertyPath, "AllOfComposition", "Merged " + composed.getAllOf().size() + " sub-schemas", ContractConfidence.HIGH));
                return new SchemaGenerationResult.Success(merged, ContractConfidence.HIGH, traces);
            }

            if (composed.getOneOf() != null && !composed.getOneOf().isEmpty()) {
                DiscriminatorResolver.DiscriminatorMatch match = discriminatorResolver.resolveBranch(
                        composed.getDiscriminator(), composed.getOneOf(), openApiSchemas
                );
                Schema<?> selected = match != null ? match.selectedBranch() : composed.getOneOf().get(0);
                SchemaGenerationResult res = generateInternal(selected, propertyPath, context, budget, random, openApiSchemas, currentDepth + 1, visitedRefs, traces);
                if (res instanceof SchemaGenerationResult.Success success && match != null && match.propertyName() != null && success.value() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) success.value();
                    map.put(match.propertyName(), match.discriminatorValue());
                }
                return res;
            }

            if (composed.getAnyOf() != null && !composed.getAnyOf().isEmpty()) {
                return generateInternal(composed.getAnyOf().get(0), propertyPath, context, budget, random, openApiSchemas, currentDepth + 1, visitedRefs, traces);
            }
        }

        // 3. Explicit examples or defaults
        if (schema.getExample() != null) {
            traces.add(new GenerationTrace(propertyPath, "SchemaExample", "Using explicit schema example", ContractConfidence.HIGH));
            return new SchemaGenerationResult.Success(schema.getExample(), ContractConfidence.HIGH, traces);
        }
        if (schema.getDefault() != null) {
            traces.add(new GenerationTrace(propertyPath, "SchemaDefault", "Using declared default value", ContractConfidence.MEDIUM));
            return new SchemaGenerationResult.Success(schema.getDefault(), ContractConfidence.MEDIUM, traces);
        }

        // 4. Enums
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            int idx = Math.abs(random.nextInt(schema.getEnum().size()));
            Object val = schema.getEnum().get(idx);
            traces.add(new GenerationTrace(propertyPath, "EnumSelection", "Selected enum value", ContractConfidence.MEDIUM));
            return new SchemaGenerationResult.Success(val, ContractConfidence.MEDIUM, traces);
        }

        // Determine logical type
        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null) type = "object";
            else if (schema.getItems() != null || schema instanceof ArraySchema) type = "array";
            else type = "string";
        }

        return switch (type.toLowerCase()) {
            case "object" -> generateObject(schema, propertyPath, context, budget, random, openApiSchemas, currentDepth, visitedRefs, traces);
            case "array" -> generateArray(schema, propertyPath, context, budget, random, openApiSchemas, currentDepth, visitedRefs, traces);
            case "integer" -> generateInteger(schema, propertyPath, random, traces);
            case "number" -> generateNumber(schema, propertyPath, random, traces);
            case "boolean" -> {
                boolean b = random.nextBoolean();
                traces.add(new GenerationTrace(propertyPath, "BooleanGenerator", "Random boolean", ContractConfidence.MEDIUM));
                yield new SchemaGenerationResult.Success(b, ContractConfidence.MEDIUM, traces);
            }
            default -> generateString(schema, propertyPath, random, traces);
        };
    }

    private SchemaGenerationResult generateObject(Schema<?> schema,
                                                  String propertyPath,
                                                  SchemaContext context,
                                                  SchemaComplexityBudget budget,
                                                  Random random,
                                                  Map<String, Schema> openApiSchemas,
                                                  int currentDepth,
                                                  Set<String> visitedRefs,
                                                  List<GenerationTrace> traces) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            // Valid empty object representation
            return new SchemaGenerationResult.Success(new LinkedHashMap<String, Object>(), ContractConfidence.HIGH, traces);
        }

        Map<String, Object> resultObject = new LinkedHashMap<>();
        List<String> requiredList = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();
        int propCount = 0;

        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Schema propSchema = entry.getValue();

            // Contextual Projection: filter readOnly in REQUEST_BODY and writeOnly in RESPONSE_BODY
            if (!context.shouldIncludeProperty(propSchema.getReadOnly(), propSchema.getWriteOnly())) {
                continue;
            }

            if (propCount >= budget.getMaxGeneratedProperties()) {
                break;
            }

            String subPath = (propertyPath == null || propertyPath.isEmpty()) ? propName : propertyPath + "." + propName;
            SchemaGenerationResult fieldRes = generateInternal(
                    propSchema, subPath, context, budget, random, openApiSchemas, currentDepth + 1, new HashSet<>(visitedRefs), traces
            );

            if (fieldRes instanceof SchemaGenerationResult.Success success) {
                resultObject.put(propName, success.value());
                propCount++;
            } else if (requiredList.contains(propName)) {
                // REQUIRED property failed generation -> Fail entire object generation, never produce scalar string!
                return new SchemaGenerationResult.GenerationFailure(
                        subPath, "Required property failed generation", "Property '" + propName + "' is required but failed"
                );
            }
        }

        traces.add(new GenerationTrace(propertyPath, "ObjectGenerator", "Generated object with " + resultObject.size() + " properties", ContractConfidence.HIGH));
        return new SchemaGenerationResult.Success(resultObject, ContractConfidence.HIGH, traces);
    }

    private SchemaGenerationResult generateArray(Schema<?> schema,
                                                 String propertyPath,
                                                 SchemaContext context,
                                                 SchemaComplexityBudget budget,
                                                 Random random,
                                                 Map<String, Schema> openApiSchemas,
                                                 int currentDepth,
                                                 Set<String> visitedRefs,
                                                 List<GenerationTrace> traces) {
        Schema<?> itemsSchema = schema.getItems();
        if (itemsSchema == null) {
            return new SchemaGenerationResult.Success(Collections.emptyList(), ContractConfidence.MEDIUM, traces);
        }

        int count = 1;
        if (schema.getMinItems() != null && schema.getMinItems() > 0) {
            count = Math.min(schema.getMinItems(), budget.getMaxGeneratedArrayItems());
        }
        if (schema.getMaxItems() != null && schema.getMaxItems() > 0 && schema.getMinItems() != null) {
            count = Math.max(count, schema.getMinItems());
        }

        boolean unique = Boolean.TRUE.equals(schema.getUniqueItems());
        Set<Object> seen = unique ? new HashSet<>() : null;
        List<Object> list = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            SchemaGenerationResult itemRes = generateInternal(
                    itemsSchema, propertyPath + "[" + i + "]", context, budget, random, openApiSchemas, currentDepth + 1, new HashSet<>(visitedRefs), traces
            );
            if (itemRes instanceof SchemaGenerationResult.Success success) {
                Object v = success.value();
                if (unique) {
                    int attempts = 0;
                    while (seen.contains(v) && attempts++ < 20) {
                        SchemaGenerationResult retryRes = generateInternal(
                                itemsSchema, propertyPath + "[" + i + "_" + attempts + "]", context, budget, random, openApiSchemas, currentDepth + 1, new HashSet<>(visitedRefs), traces
                        );
                        if (retryRes instanceof SchemaGenerationResult.Success s) v = s.value();
                    }
                    seen.add(v);
                }
                list.add(v);
            }
        }

        return new SchemaGenerationResult.Success(list, ContractConfidence.HIGH, traces);
    }

    private SchemaGenerationResult generateString(Schema<?> schema, String propertyPath, Random random, List<GenerationTrace> traces) {
        String format = schema.getFormat();
        String pattern = schema.getPattern();

        // Sensitive field awareness
        if (sensitiveDataClassifier.isSensitive(propertyPath)) {
            String dummySecret = sensitiveDataClassifier.generateSafeDummy(propertyPath, random);
            traces.add(new GenerationTrace(propertyPath, "SensitiveClassifier", "Generated safe dummy credential", ContractConfidence.HIGH));
            return new SchemaGenerationResult.Success(dummySecret, ContractConfidence.HIGH, traces);
        }

        if (pattern != null && !pattern.isBlank()) {
            String val = patternGenerator.generateMatchingValue(pattern, random,
                    schema.getMinLength() != null ? schema.getMinLength() : 0,
                    schema.getMaxLength() != null ? schema.getMaxLength() : 50);
            traces.add(new GenerationTrace(propertyPath, "PatternGenerator", "Generated pattern-satisfying string", ContractConfidence.MEDIUM));
            return new SchemaGenerationResult.Success(val, ContractConfidence.MEDIUM, traces);
        }

        String prop = propertyPath != null ? propertyPath.toLowerCase() : "";

        if ("uuid".equalsIgnoreCase(format) || prop.endsWith("id") || prop.contains("uuid")) {
            long mostSigBits = random.nextLong();
            long leastSigBits = random.nextLong();
            String uuid = new UUID(mostSigBits, leastSigBits).toString();
            return new SchemaGenerationResult.Success(uuid, ContractConfidence.HIGH, traces);
        }

        if ("email".equalsIgnoreCase(format) || prop.contains("email")) {
            String email = "test_user_" + Math.abs(random.nextInt(10000)) + "@syedqa.test";
            return new SchemaGenerationResult.Success(email, ContractConfidence.HIGH, traces);
        }

        if ("date-time".equalsIgnoreCase(format) || prop.contains("time") || prop.contains("createdat") || prop.contains("timestamp")) {
            long epochSeconds = 1704067200L + (Math.abs(random.nextLong()) % 31536000L);
            String dt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), java.time.ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return new SchemaGenerationResult.Success(dt, ContractConfidence.HIGH, traces);
        }

        if ("date".equalsIgnoreCase(format) || prop.contains("date")) {
            long epochDays = 19723L + (Math.abs(random.nextInt(365)));
            String d = LocalDate.ofEpochDay(epochDays).format(DateTimeFormatter.ISO_LOCAL_DATE);
            return new SchemaGenerationResult.Success(d, ContractConfidence.HIGH, traces);
        }

        if ("uri".equalsIgnoreCase(format) || "url".equalsIgnoreCase(format)) {
            return new SchemaGenerationResult.Success("https://api.example.com/item/" + Math.abs(random.nextInt(1000)), ContractConfidence.HIGH, traces);
        }

        String fallback = (propertyPath != null ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1) : "val") + "_" + Math.abs(random.nextInt(1000));
        return new SchemaGenerationResult.Success(fallback, ContractConfidence.MEDIUM, traces);
    }

    private SchemaGenerationResult generateInteger(Schema<?> schema, String propertyPath, Random random, List<GenerationTrace> traces) {
        long min = schema.getMinimum() != null ? schema.getMinimum().longValue() : 1L;
        long max = schema.getMaximum() != null ? schema.getMaximum().longValue() : 50L;
        if (schema.getExclusiveMinimumValue() != null) min = schema.getExclusiveMinimumValue().longValue() + 1L;
        if (schema.getExclusiveMaximumValue() != null) max = schema.getExclusiveMaximumValue().longValue() - 1L;
        if (max < min) max = min;

        long range = Math.max(1, max - min + 1);
        long val = min + (Math.abs(random.nextLong()) % range);
        traces.add(new GenerationTrace(propertyPath, "IntegerConstraintGenerator", "Generated integer within [" + min + ", " + max + "]", ContractConfidence.MEDIUM));
        return new SchemaGenerationResult.Success(val, ContractConfidence.MEDIUM, traces);
    }

    private SchemaGenerationResult generateNumber(Schema<?> schema, String propertyPath, Random random, List<GenerationTrace> traces) {
        double min = schema.getMinimum() != null ? schema.getMinimum().doubleValue() : 1.0;
        double max = schema.getMaximum() != null ? schema.getMaximum().doubleValue() : 100.0;
        double val = min + (random.nextDouble() * (max - min));
        traces.add(new GenerationTrace(propertyPath, "NumberConstraintGenerator", "Generated number within range", ContractConfidence.MEDIUM));
        return new SchemaGenerationResult.Success(Math.round(val * 100.0) / 100.0, ContractConfidence.MEDIUM, traces);
    }
}
