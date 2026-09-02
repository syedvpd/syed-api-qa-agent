package com.syed.apiqa.contract.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class JsonPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper objectMapper;

    public JsonPayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String mediaType) {
        if (mediaType == null) return false;
        String lower = mediaType.toLowerCase();
        return lower.contains("application/json") || lower.contains("+json");
    }

    @Override
    public byte[] serialize(Object data) throws Exception {
        if (data == null) return new byte[0];
        if (data instanceof byte[] b) return b;
        if (data instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return trimmed.getBytes(StandardCharsets.UTF_8);
            }
            return objectMapper.writeValueAsBytes(data);
        }
        return objectMapper.writeValueAsBytes(data);
    }

    @Override
    public String getEffectiveContentType() {
        return "application/json; charset=UTF-8";
    }
}
