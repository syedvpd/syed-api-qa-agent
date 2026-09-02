package com.syed.apiqa.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.*;

/**
 * Universal Normalized Response Representation.
 * Encapsulates the HTTP response state with parsed AST and schema validation findings.
 */
public class ResponseRepresentation implements Serializable {

    private int statusCode;
    private Map<String, List<String>> headers = new LinkedHashMap<>();
    private Map<String, String> cookies = new LinkedHashMap<>();
    private String mediaType;
    private byte[] bodyBytes = new byte[0];
    private String rawBodyString;
    private JsonNode parsedJson;
    private long durationMs;
    private List<String> validationFindings = new ArrayList<>();

    public ResponseRepresentation() {}

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public Map<String, List<String>> getHeaders() { return headers; }
    public void setHeaders(Map<String, List<String>> headers) { this.headers = headers; }

    public Map<String, String> getCookies() { return cookies; }
    public void setCookies(Map<String, String> cookies) { this.cookies = cookies; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public byte[] getBodyBytes() { return bodyBytes; }
    public void setBodyBytes(byte[] bodyBytes) { this.bodyBytes = bodyBytes != null ? bodyBytes : new byte[0]; }

    public String getRawBodyString() { return rawBodyString; }
    public void setRawBodyString(String rawBodyString) { this.rawBodyString = rawBodyString; }

    public JsonNode getParsedJson() { return parsedJson; }
    public void setParsedJson(JsonNode parsedJson) { this.parsedJson = parsedJson; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public List<String> getValidationFindings() { return validationFindings; }
    public void setValidationFindings(List<String> validationFindings) { this.validationFindings = validationFindings != null ? validationFindings : new ArrayList<>(); }
    public void addValidationFinding(String finding) { if (finding != null) this.validationFindings.add(finding); }
}
