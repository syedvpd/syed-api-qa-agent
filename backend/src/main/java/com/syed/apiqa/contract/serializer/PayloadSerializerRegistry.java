package com.syed.apiqa.contract.serializer;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PayloadSerializerRegistry {

    private final List<PayloadSerializer> serializers;
    private final JsonPayloadSerializer defaultJsonSerializer;

    public PayloadSerializerRegistry(List<PayloadSerializer> serializers, JsonPayloadSerializer defaultJsonSerializer) {
        this.serializers = serializers;
        this.defaultJsonSerializer = defaultJsonSerializer;
    }

    public PayloadSerializer getSerializer(String mediaType) {
        if (mediaType != null) {
            for (PayloadSerializer s : serializers) {
                if (s.supports(mediaType)) {
                    return s;
                }
            }
        }
        return defaultJsonSerializer;
    }
}
