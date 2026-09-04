package com.syed.apiqa.discovery;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the result of an OpenAPI specification discovery operation.
 * Tracks original user input URL, final resolved spec URL, discovery method, and raw content.
 */
public class OpenApiDiscoveryResult {

    public enum DiscoveryMethod {
        DIRECT_SPEC,
        HTML_REDISCOVERY,
        COMMON_PATH_FALLBACK
    }

    private final String originalUrl;
    private final String discoveredSpecUrl;
    private final DiscoveryMethod discoveryMethod;
    private final String content;
    private final List<String> candidatesAttempted;

    public OpenApiDiscoveryResult(String originalUrl,
                                  String discoveredSpecUrl,
                                  DiscoveryMethod discoveryMethod,
                                  String content,
                                  List<String> candidatesAttempted) {
        this.originalUrl = originalUrl;
        this.discoveredSpecUrl = discoveredSpecUrl;
        this.discoveryMethod = discoveryMethod;
        this.content = content;
        this.candidatesAttempted = candidatesAttempted != null ? List.copyOf(candidatesAttempted) : Collections.emptyList();
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getDiscoveredSpecUrl() {
        return discoveredSpecUrl;
    }

    public DiscoveryMethod getDiscoveryMethod() {
        return discoveryMethod;
    }

    public String getContent() {
        return content;
    }

    public List<String> getCandidatesAttempted() {
        return candidatesAttempted;
    }
}
