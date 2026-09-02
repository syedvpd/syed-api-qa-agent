package com.syed.apiqa.contract.serializer;

import com.syed.apiqa.domain.canonical.ContractCapability;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Truthful XML Serializer.
 * Supports basic root-element tag wrapping for maps and reports PARTIAL status
 * without pretending full arbitrary XSD/XML Schema namespaces are solved.
 */
@Component
public class XmlPayloadSerializer implements PayloadSerializer {

    public ContractCapability.SupportLevel getSupportLevel() {
        return ContractCapability.SupportLevel.PARTIAL;
    }

    public String getCapabilityDetails() {
        return "Basic element tag wrapping supported; complex XSD namespaces and attributes reported as PARTIAL.";
    }

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && (mediaType.toLowerCase().contains("application/xml") || mediaType.toLowerCase().contains("text/xml"));
    }

    @Override
    public byte[] serialize(Object data) throws Exception {
        if (data == null) return "<root/>".getBytes(StandardCharsets.UTF_8);
        if (data instanceof byte[] b) return b;
        if (data instanceof String s && s.trim().startsWith("<")) return s.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n");

        if (data instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String tag = String.valueOf(entry.getKey()).replaceAll("[^a-zA-Z0-9_.-]", "_");
                String val = String.valueOf(entry.getValue())
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
                sb.append("  <").append(tag).append(">").append(val).append("</").append(tag).append(">\n");
            }
        } else {
            sb.append("  <value>").append(String.valueOf(data)).append("</value>\n");
        }

        sb.append("</root>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getEffectiveContentType() {
        return "application/xml; charset=UTF-8";
    }
}
