package com.syed.apiqa.contract.serializer;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Component
public class MultipartPayloadSerializer implements PayloadSerializer {

    private String lastBoundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.toLowerCase().contains("multipart/form-data");
    }

    @Override
    public byte[] serialize(Object data) throws Exception {
        lastBoundary = "----SyedQaBoundary" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        if (data instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object val = entry.getValue();

                baos.write(("--" + lastBoundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (name.toLowerCase().contains("file") || (val instanceof byte[])) {
                    baos.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name + ".dat\"\r\n").getBytes(StandardCharsets.UTF_8));
                    baos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    if (val instanceof byte[] bytes) {
                        baos.write(bytes);
                    } else {
                        baos.write(String.valueOf(val).getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    baos.write(String.valueOf(val).getBytes(StandardCharsets.UTF_8));
                }
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            baos.write(("--" + lastBoundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write("Content-Disposition: form-data; name=\"payload\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            baos.write(String.valueOf(data).getBytes(StandardCharsets.UTF_8));
            baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        baos.write(("--" + lastBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    @Override
    public String getEffectiveContentType() {
        return "multipart/form-data; boundary=" + lastBoundary;
    }
}
