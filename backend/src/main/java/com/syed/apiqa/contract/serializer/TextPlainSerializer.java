package com.syed.apiqa.contract.serializer;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TextPlainSerializer implements PayloadSerializer {

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.toLowerCase().contains("text/plain");
    }

    @Override
    public byte[] serialize(Object data) throws Exception {
        if (data == null) return new byte[0];
        if (data instanceof byte[] b) return b;
        return String.valueOf(data).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getEffectiveContentType() {
        return "text/plain; charset=UTF-8";
    }
}
