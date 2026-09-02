package com.syed.apiqa.domain;

import java.io.Serializable;
import java.util.*;

/**
 * Universal, transmission-ready Request Representation.
 * Encapsulates the fully resolved request before and after serialization,
 * preserving metadata, confidence, and provenance traces without leaking secrets.
 */
public class RequestRepresentation implements Serializable {

    private String method;
    private String url;
    private Map<String, List<String>> headers = new LinkedHashMap<>();
    private Map<String, String> cookies = new LinkedHashMap<>();
    private String mediaType;
    private byte[] bodyBytes = new byte[0];
    private Object preSerializationBody;
    private String identityName;
    private ContractConfidence confidence = ContractConfidence.HIGH;
    private List<GenerationTrace> traces = new ArrayList<>();

    public RequestRepresentation() {}

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, List<String>> getHeaders() { return headers; }
    public void setHeaders(Map<String, List<String>> headers) { this.headers = headers; }

    public void addHeader(String name, String value) {
        if (name != null && value != null) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    public Map<String, String> getCookies() { return cookies; }
    public void setCookies(Map<String, String> cookies) { this.cookies = cookies; }

    public void addCookie(String name, String value) {
        if (name != null && value != null) {
            cookies.put(name, value);
        }
    }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public byte[] getBodyBytes() { return bodyBytes; }
    public void setBodyBytes(byte[] bodyBytes) { this.bodyBytes = bodyBytes != null ? bodyBytes : new byte[0]; }

    public Object getPreSerializationBody() { return preSerializationBody; }
    public void setPreSerializationBody(Object preSerializationBody) { this.preSerializationBody = preSerializationBody; }

    public String getIdentityName() { return identityName; }
    public void setIdentityName(String identityName) { this.identityName = identityName; }

    public ContractConfidence getConfidence() { return confidence; }
    public void setConfidence(ContractConfidence confidence) { this.confidence = confidence; }

    public List<GenerationTrace> getTraces() { return traces; }
    public void setTraces(List<GenerationTrace> traces) { this.traces = traces != null ? traces : new ArrayList<>(); }
    public void addTrace(GenerationTrace trace) { if (trace != null) this.traces.add(trace); }
}
