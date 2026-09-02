package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Universal Token Extractor.
 * Extracts tokens from complex nested JSON AST structures using dot notation
 * or heuristic scanning across token-bearing property signals.
 */
@Component
public class TokenExtractor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> COMMON_TOKEN_KEYS = List.of(
            "access_token", "accessToken", "token", "jwt", "id_token", "idToken", "sessionToken", "auth_token", "key"
    );

    public record ExtractedToken(String tokenValue, String tokenType, String sourcePath, Long expiresInSeconds) {}

    public ExtractedToken extract(String responseJson, String explicitPath) {
        if (responseJson == null || responseJson.isBlank()) return null;

        try {
            JsonNode root = objectMapper.readTree(responseJson);

            // 1. If explicit dot-path provided, traverse it
            if (explicitPath != null && !explicitPath.isBlank() && !"token".equalsIgnoreCase(explicitPath)) {
                String normalizedPath = explicitPath.startsWith("$.") ? explicitPath.substring(2) : explicitPath;
                String[] segments = normalizedPath.split("\\.");
                JsonNode current = root;
                for (String seg : segments) {
                    if (current != null && current.has(seg)) {
                        current = current.get(seg);
                    } else {
                        current = null;
                        break;
                    }
                }
                if (current != null && current.isValueNode()) {
                    return new ExtractedToken(current.asText(), "Bearer", explicitPath, extractExpiresIn(root));
                }
            }

            // 2. Direct property scan
            for (String key : COMMON_TOKEN_KEYS) {
                if (root.has(key) && root.get(key).isValueNode()) {
                    return new ExtractedToken(root.get(key).asText(), "Bearer", "$." + key, extractExpiresIn(root));
                }
            }

            // 3. Nested object scan (e.g. data.token, response.accessToken)
            if (root.has("data") && root.get("data").isObject()) {
                JsonNode dataNode = root.get("data");
                for (String key : COMMON_TOKEN_KEYS) {
                    if (dataNode.has(key) && dataNode.get(key).isValueNode()) {
                        return new ExtractedToken(dataNode.get(key).asText(), "Bearer", "$.data." + key, extractExpiresIn(dataNode));
                    }
                }
            }

            if (root.has("result") && root.get("result").isObject()) {
                JsonNode resultNode = root.get("result");
                for (String key : COMMON_TOKEN_KEYS) {
                    if (resultNode.has(key) && resultNode.get(key).isValueNode()) {
                        return new ExtractedToken(resultNode.get(key).asText(), "Bearer", "$.result." + key, extractExpiresIn(resultNode));
                    }
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractExpiresIn(JsonNode node) {
        if (node.has("expires_in")) return node.get("expires_in").asLong();
        if (node.has("expiresIn")) return node.get("expiresIn").asLong();
        return null;
    }
}
