package com.syed.apiqa.contract.serializer;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

@Component
public class FormUrlEncodedSerializer implements PayloadSerializer {

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.toLowerCase().contains("application/x-www-form-urlencoded");
    }

    @Override
    public byte[] serialize(Object data) throws Exception {
        if (data == null) return new byte[0];
        if (data instanceof byte[] b) return b;
        if (data instanceof String s && s.contains("=")) return s.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        if (data instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object val = entry.getValue();

                if (val instanceof Collection<?> col) {
                    for (Object item : col) {
                        appendField(sb, key, item);
                    }
                } else {
                    appendField(sb, key, val);
                }
            }
        } else {
            sb.append("data=").append(URLEncoder.encode(String.valueOf(data), StandardCharsets.UTF_8));
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendField(StringBuilder sb, String key, Object val) {
        if (sb.length() > 0) sb.append("&");
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String encodedVal = val != null ? URLEncoder.encode(String.valueOf(val), StandardCharsets.UTF_8) : "";
        sb.append(encodedKey).append("=").append(encodedVal);
    }

    @Override
    public String getEffectiveContentType() {
        return "application/x-www-form-urlencoded; charset=UTF-8";
    }
}
