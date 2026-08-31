package com.syed.apiqa.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic Negative & Boundary Data Generator.
 * Synthesizes safe invalid variants for API validation robustness testing:
 * - Missing required fields
 * - Wrong data types
 * - Out-of-bounds boundary values (minimum - 1, maximum + 1, minLength - 1, maxLength + 1)
 * - Invalid enums & invalid formats (email, uuid, date)
 * - Malformed payloads (empty, corrupted syntax)
 * - Safe non-destructive security probes
 */
@Service
public class NegativeDataGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeterministicDataGenerator deterministicDataGenerator;

    public NegativeDataGenerator(DeterministicDataGenerator deterministicDataGenerator) {
        this.deterministicDataGenerator = deterministicDataGenerator;
    }

    public static class NegativePayload {
        private final String scenarioName;
        private final String payloadJson;
        private final int expectedNegativeStatus; // 400 or 422
        private final String rationale;

        public NegativePayload(String scenarioName, String payloadJson, int expectedNegativeStatus, String rationale) {
            this.scenarioName = scenarioName;
            this.payloadJson = payloadJson;
            this.expectedNegativeStatus = expectedNegativeStatus;
            this.rationale = rationale;
        }

        public String getScenarioName() { return scenarioName; }
        public String getPayloadJson() { return payloadJson; }
        public int getExpectedNegativeStatus() { return expectedNegativeStatus; }
        public String getRationale() { return rationale; }
    }

    /**
     * Generates a suite of deterministic negative variants for a request schema.
     */
    public List<NegativePayload> generateNegativeVariants(String validJsonBody, String schemaContent) {
        List<NegativePayload> variants = new ArrayList<>();
        if (validJsonBody == null || validJsonBody.isBlank() || schemaContent == null || schemaContent.isBlank()) {
            return variants;
        }

        try {
            JsonNode schemaNode = objectMapper.readTree(schemaContent);
            JsonNode validNode = objectMapper.readTree(validJsonBody);

            if (validNode.isObject() && schemaNode.isObject()) {
                // 1. Missing Required Field Variants
                generateMissingRequiredFields((ObjectNode) validNode, schemaNode, variants);

                // 2. Wrong Type Variants
                generateWrongTypes((ObjectNode) validNode, schemaNode, variants);

                // 3. Invalid Enum Variants
                generateInvalidEnums((ObjectNode) validNode, schemaNode, variants);

                // 4. Boundary Violation Variants
                generateBoundaryViolations((ObjectNode) validNode, schemaNode, variants);

                // 5. Invalid Format Variants (email, uuid, date)
                generateInvalidFormats((ObjectNode) validNode, schemaNode, variants);
            }

            // 6. Malformed JSON & Structural Inputs
            variants.add(new NegativePayload("Malformed JSON Syntax", "{\"bad_json\": ,}", 400, "Corrupted JSON syntax must be rejected"));
            variants.add(new NegativePayload("Empty JSON Object", "{}", 400, "Empty payload when fields are required"));
            variants.add(new NegativePayload("Null Payload", "null", 400, "Null body rejection check"));

            // 7. Safe Non-destructive Security Probes
            generateSecurityProbes((ObjectNode) validNode, variants);

        } catch (Exception ignored) {}

        return variants;
    }

    private void generateMissingRequiredFields(ObjectNode validNode, JsonNode schemaNode, List<NegativePayload> variants) {
        JsonNode requiredArray = schemaNode.get("required");
        if (requiredArray != null && requiredArray.isArray()) {
            for (JsonNode req : requiredArray) {
                String fieldName = req.asText();
                if (validNode.has(fieldName)) {
                    // 1. Missing required field
                    ObjectNode missingField = validNode.deepCopy();
                    missingField.remove(fieldName);
                    variants.add(new NegativePayload(
                            "Missing required field: " + fieldName,
                            missingField.toString(),
                            422,
                            "Dropping mandatory field '" + fieldName + "' must return 400 or 422"
                    ));

                    // 2. Null required field
                    ObjectNode nullField = validNode.deepCopy();
                    nullField.putNull(fieldName);
                    variants.add(new NegativePayload(
                            "Null required field: " + fieldName,
                            nullField.toString(),
                            422,
                            "Passing null for mandatory field '" + fieldName + "' must be rejected"
                    ));

                    // 3. Empty string for required string field
                    if (validNode.get(fieldName).isTextual()) {
                        ObjectNode emptyField = validNode.deepCopy();
                        emptyField.put(fieldName, "");
                        variants.add(new NegativePayload(
                            "Empty string for required field: " + fieldName,
                            emptyField.toString(),
                            422,
                            "Passing empty string for mandatory field '" + fieldName + "' must be rejected"
                        ));
                    }
                }
            }
        }
    }

    private void generateWrongTypes(ObjectNode validNode, JsonNode schemaNode, List<NegativePayload> variants) {
        JsonNode properties = schemaNode.get("properties");
        if (properties == null || !properties.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            String type = field.getValue().has("type") ? field.getValue().get("type").asText() : "";

            if (!validNode.has(fieldName)) continue;

            ObjectNode mutated = validNode.deepCopy();
            if ("integer".equals(type) || "number".equals(type)) {
                mutated.put(fieldName, "not-a-number");
                variants.add(new NegativePayload("Wrong type for " + fieldName + " (string instead of number)",
                        mutated.toString(), 400, "Sending string into numeric field"));
                break;
            } else if ("string".equals(type)) {
                mutated.put(fieldName, 12345);
                variants.add(new NegativePayload("Wrong type for " + fieldName + " (number instead of string)",
                        mutated.toString(), 400, "Sending numeric literal into string field"));
                break;
            } else if ("boolean".equals(type)) {
                mutated.put(fieldName, 9999);
                variants.add(new NegativePayload("Wrong type for " + fieldName + " (number instead of boolean)",
                        mutated.toString(), 400, "Sending integer into boolean field"));
                break;
            }
        }
    }

    private void generateInvalidEnums(ObjectNode validNode, JsonNode schemaNode, List<NegativePayload> variants) {
        JsonNode properties = schemaNode.get("properties");
        if (properties == null || !properties.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode prop = field.getValue();

            if (prop.hasNonNull("enum") && prop.get("enum").isArray() && !prop.get("enum").isEmpty()) {
                ObjectNode mutated = validNode.deepCopy();
                mutated.put(fieldName, "__INVALID_ENUM_VALUE__");
                variants.add(new NegativePayload("Invalid enum for " + fieldName,
                        mutated.toString(), 400, "Enum violation check"));
                break;
            }
        }
    }

    private void generateBoundaryViolations(ObjectNode validNode, JsonNode schemaNode, List<NegativePayload> variants) {
        JsonNode properties = schemaNode.get("properties");
        if (properties == null || !properties.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode prop = field.getValue();
            String type = prop.hasNonNull("type") ? prop.get("type").asText().toLowerCase() : "";

            // Numeric minimum / maximum
            if (("integer".equals(type) || "number".equals(type)) && prop.hasNonNull("minimum")) {
                long min = prop.get("minimum").asLong();
                ObjectNode mutated = validNode.deepCopy();
                mutated.put(fieldName, min - 1);
                variants.add(new NegativePayload("Underflow boundary for " + fieldName + " (min - 1)",
                        mutated.toString(), 422, "Minimum boundary limit violation"));
            }
            if (("integer".equals(type) || "number".equals(type)) && prop.hasNonNull("maximum")) {
                long max = prop.get("maximum").asLong();
                ObjectNode mutated = validNode.deepCopy();
                mutated.put(fieldName, max + 1);
                variants.add(new NegativePayload("Overflow boundary for " + fieldName + " (max + 1)",
                        mutated.toString(), 422, "Maximum boundary limit violation"));
            }

            // String minLength / maxLength
            if ("string".equals(type) && prop.hasNonNull("maxLength")) {
                int maxLen = prop.get("maxLength").asInt();
                ObjectNode mutated = validNode.deepCopy();
                mutated.put(fieldName, "X".repeat(maxLen + 10));
                variants.add(new NegativePayload("String maxLength overflow for " + fieldName,
                        mutated.toString(), 422, "Exceeding maxLength constraint"));
            }
        }
    }

    private void generateInvalidFormats(ObjectNode validNode, JsonNode schemaNode, List<NegativePayload> variants) {
        JsonNode properties = schemaNode.get("properties");
        if (properties == null || !properties.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode prop = field.getValue();

            if (prop.hasNonNull("format")) {
                String format = prop.get("format").asText();
                ObjectNode mutated = validNode.deepCopy();

                if ("email".equalsIgnoreCase(format)) {
                    mutated.put(fieldName, "invalid-email-format-without-at");
                    variants.add(new NegativePayload("Invalid email format for " + fieldName,
                            mutated.toString(), 422, "Format email RFC compliance rejection"));
                } else if ("uuid".equalsIgnoreCase(format)) {
                    mutated.put(fieldName, "1234-invalid-uuid");
                    variants.add(new NegativePayload("Invalid UUID format for " + fieldName,
                            mutated.toString(), 422, "UUID format compliance rejection"));
                } else if ("date".equalsIgnoreCase(format) || "date-time".equalsIgnoreCase(format)) {
                    mutated.put(fieldName, "not-a-valid-date");
                    variants.add(new NegativePayload("Invalid date format for " + fieldName,
                            mutated.toString(), 422, "ISO date format compliance rejection"));
                }
            }
        }
    }

    private void generateSecurityProbes(ObjectNode validNode, List<NegativePayload> variants) {
        // Safe injection & script markers in string field
        Iterator<Map.Entry<String, JsonNode>> fields = validNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isTextual()) {
                String fieldName = field.getKey();

                // Safe SQL marker
                ObjectNode sqli = validNode.deepCopy();
                sqli.put(fieldName, "' OR '1'='1");
                variants.add(new NegativePayload("Safe SQL marker probe in " + fieldName,
                        sqli.toString(), 400, "Validation of input against injection characters"));

                // Safe HTML/script tag
                ObjectNode xss = validNode.deepCopy();
                xss.put(fieldName, "<script>alert('test')</script>");
                variants.add(new NegativePayload("Safe script tag probe in " + fieldName,
                        xss.toString(), 400, "Validation of input against script tags"));

                break; // One field probe is sufficient for API QA robustness
            }
        }
    }
}
