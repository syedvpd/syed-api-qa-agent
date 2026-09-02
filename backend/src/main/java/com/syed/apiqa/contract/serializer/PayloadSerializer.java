package com.syed.apiqa.contract.serializer;

import java.nio.charset.StandardCharsets;

/**
 * Strategy interface for media type payload serialization.
 */
public interface PayloadSerializer {

    boolean supports(String mediaType);

    byte[] serialize(Object data) throws Exception;

    String getEffectiveContentType();
}
