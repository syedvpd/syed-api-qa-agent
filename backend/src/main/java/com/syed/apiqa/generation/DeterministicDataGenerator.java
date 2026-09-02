package com.syed.apiqa.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Pure deterministic, zero-LLM schema-driven test data generator.
 * Produces valid, reproducible, collision-free payloads using a seeded pseudo-random engine.
 */
@Service
public class DeterministicDataGenerator {

    private final ObjectMapper objectMapper;

    public DeterministicDataGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Object generateValueForSchema(Schema<?> schema, String propertyName, Random random, String runIdPrefix) {
        return generateValueForSchema(schema, propertyName, random, runIdPrefix, null);
    }

    public Object generateValueForSchema(Schema<?> schema, String propertyName, Random random, String runIdPrefix, Map<String, Schema> openApiSchemas) {
        if (schema == null) {
            return "test_val_" + Math.abs(random.nextInt(10000));
        }

        // 0. Dereference $ref if present
        if (schema.get$ref() != null && openApiSchemas != null) {
            String refKey = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            Schema<?> target = openApiSchemas.get(refKey);
            if (target != null) {
                return generateValueForSchema(target, propertyName, random, runIdPrefix, openApiSchemas);
            }
        }

        // Handle ComposedSchema (allOf, oneOf, anyOf)
        if (schema instanceof io.swagger.v3.oas.models.media.ComposedSchema composed) {
            if (composed.getAllOf() != null && !composed.getAllOf().isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>();
                for (Schema<?> sub : composed.getAllOf()) {
                    Object val = generateValueForSchema(sub, propertyName, random, runIdPrefix, openApiSchemas);
                    if (val instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            merged.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                }
                return merged;
            }
            if (composed.getOneOf() != null && !composed.getOneOf().isEmpty()) {
                return generateValueForSchema(composed.getOneOf().get(0), propertyName, random, runIdPrefix, openApiSchemas);
            }
            if (composed.getAnyOf() != null && !composed.getAnyOf().isEmpty()) {
                return generateValueForSchema(composed.getAnyOf().get(0), propertyName, random, runIdPrefix, openApiSchemas);
            }
        }

        // 1. If explicit example or default exists, prefer it
        if (schema.getExample() != null) {
            return schema.getExample();
        }
        if (schema.getDefault() != null) {
            return schema.getDefault();
        }

        // 2. If enum exists, pick deterministically based on seed
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            int index = Math.abs(random.nextInt(schema.getEnum().size()));
            return schema.getEnum().get(index);
        }

        String type = schema.getType();
        String format = schema.getFormat();

        if (type == null) {
            if (schema.getProperties() != null) type = "object";
            else if (schema.getItems() != null) type = "array";
            else type = "string";
        }

        switch (type.toLowerCase()) {
            case "string":
                return generateString(schema, propertyName, format, random, runIdPrefix);
            case "integer":
                return generateInteger(schema, random);
            case "number":
                return generateNumber(schema, random);
            case "boolean":
                return random.nextBoolean();
            case "array":
                return generateArray(schema, propertyName, random, runIdPrefix, openApiSchemas);
            case "object":
                return generateObject(schema, random, runIdPrefix, openApiSchemas);
            default:
                return "test_" + (propertyName != null ? propertyName : "val") + "_" + Math.abs(random.nextInt(1000));
        }
    }

    private Object generateString(Schema<?> schema, String propertyName, String format, Random random, String runIdPrefix) {
        String prop = propertyName != null ? propertyName.toLowerCase() : "";

        if ("uuid".equalsIgnoreCase(format) || prop.contains("uuid")) {
            long mostSigBits = random.nextLong();
            long leastSigBits = random.nextLong();
            return new UUID(mostSigBits, leastSigBits).toString();
        }

        if ("email".equalsIgnoreCase(format) || prop.contains("email")) {
            return "qa_" + runIdPrefix + "_" + Math.abs(random.nextInt(10000)) + "@syedqa.test";
        }

        if ("date-time".equalsIgnoreCase(format) || prop.contains("time") || prop.contains("timestamp")) {
            // Pure deterministic date-time derived from seeded random (UTC anchor 2024-01-01)
            long epochSeconds = 1704067200L + (Math.abs(random.nextLong()) % 31536000L);
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), java.time.ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        if ("date".equalsIgnoreCase(format) || prop.contains("date")) {
            // Pure deterministic date derived from seeded random (anchor day 19723 = 2024-01-01)
            long epochDays = 19723L + (Math.abs(random.nextInt(365)));
            return LocalDate.ofEpochDay(epochDays).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        if ("uri".equalsIgnoreCase(format) || "url".equalsIgnoreCase(format) || prop.contains("url")) {
            return "https://api.test.example.com/res/" + Math.abs(random.nextInt(1000));
        }

        // Semantic field matching
        if (prop.contains("name")) {
            String[] names = {"Alex Rivera", "Jordan Lee", "Taylor Smith", "Casey Morgan", "Sam Patel"};
            return names[Math.abs(random.nextInt(names.length))] + " " + Math.abs(random.nextInt(1000));
        }
        if (prop.contains("phone")) {
            return "+1555" + String.format("%07d", Math.abs(random.nextInt(10000000)));
        }
        if (prop.contains("city")) return "Metropolis";
        if (prop.contains("country")) return "US";
        if (prop.contains("zip") || prop.contains("postal")) return "90210";

        // General string respecting min/maxLength
        int min = schema.getMinLength() != null ? schema.getMinLength() : 3;
        int max = schema.getMaxLength() != null ? schema.getMaxLength() : 30;
        int targetLen = Math.max(min, Math.min(12, max));

        String base = (propertyName != null ? propertyName : "str") + "_" + runIdPrefix + "_" + Math.abs(random.nextInt(10000));
        if (base.length() > max) {
            return base.substring(0, max);
        }
        while (base.length() < min) {
            base = base + "x";
        }
        return base;
    }

    private Long generateInteger(Schema<?> schema, Random random) {
        long min = schema.getMinimum() != null ? schema.getMinimum().longValue() : 1L;
        long max = schema.getMaximum() != null ? schema.getMaximum().longValue() : 1000L;
        if (max < min) max = min + 100;
        long range = Math.max(1L, (max - min));
        return min + (Math.abs(random.nextLong()) % range);
    }

    private Double generateNumber(Schema<?> schema, Random random) {
        double min = schema.getMinimum() != null ? schema.getMinimum().doubleValue() : 1.0;
        double max = schema.getMaximum() != null ? schema.getMaximum().doubleValue() : 500.0;
        if (max < min) max = min + 10.0;
        double val = min + (random.nextDouble() * (max - min));
        return Math.round(val * 100.0) / 100.0;
    }

    private List<Object> generateArray(Schema<?> schema, String propertyName, Random random, String runIdPrefix, Map<String, Schema> openApiSchemas) {
        List<Object> list = new ArrayList<>();
        Schema<?> itemsSchema = schema.getItems();
        int count = schema.getMinItems() != null ? Math.max(1, schema.getMinItems()) : 1;
        if (schema.getMaxItems() != null && count > schema.getMaxItems()) {
            count = schema.getMaxItems();
        }

        Boolean uniqueItems = schema.getUniqueItems();
        Set<Object> seen = (uniqueItems != null && uniqueItems) ? new LinkedHashSet<>() : null;

        for (int i = 0; i < count; i++) {
            Object val = generateValueForSchema(itemsSchema, propertyName + "_item", random, runIdPrefix, openApiSchemas);
            if (seen != null) {
                int attempts = 0;
                while (seen.contains(val) && attempts < 10) {
                    val = generateValueForSchema(itemsSchema, propertyName + "_item_" + attempts, random, runIdPrefix, openApiSchemas);
                    attempts++;
                }
                seen.add(val);
                list.add(val);
            } else {
                list.add(val);
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateObject(Schema<?> schema, Random random, String runIdPrefix, Map<String, Schema> openApiSchemas) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schema == null) return map;

        if (schema.get$ref() != null && openApiSchemas != null) {
            String refKey = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            Schema<?> target = openApiSchemas.get(refKey);
            if (target != null) {
                return generateObject(target, random, runIdPrefix, openApiSchemas);
            }
        }

        if (schema.getProperties() == null) {
            return map;
        }

        Map<String, Schema> properties = schema.getProperties();
        List<String> required = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();

        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Schema propSchema = entry.getValue();

            boolean isRequired = required.contains(propName);
            if (isRequired || random.nextInt(100) < 75) {
                Object val = generateValueForSchema(propSchema, propName, random, runIdPrefix, openApiSchemas);
                map.put(propName, val);
            }
        }
        return map;
    }

    public Map<String, Object> generateObject(Schema<?> schema, Random random, String runIdPrefix) {
        return generateObject(schema, random, runIdPrefix, null);
    }

    public String generateJsonString(Schema<?> schema, String runId) {
        return generateJsonString(schema, runId, null);
    }

    public String generateJsonString(Schema<?> schema, String runId, Map<String, Schema> openApiSchemas) {
        long seed = (runId != null ? runId.hashCode() : 42L);
        Random random = new Random(seed);
        String prefix = runId != null && runId.length() >= 6 ? runId.substring(0, 6) : "test";

        Object data = generateValueForSchema(schema, "root", random, prefix, openApiSchemas);
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
