package com.syed.apiqa.planning.dag;

import com.syed.apiqa.domain.ConfidenceLevel;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a directed dependency edge from a producer node to a consumer node.
 */
public class DagEdge implements Serializable {

    public enum ParameterLocation {
        PATH,
        QUERY,
        HEADER,
        BODY,
        AUTH
    }

    private final String producerNodeId;
    private final String consumerNodeId;
    private final String parameterName;
    private final String sourceField;
    private final ParameterLocation location;
    private final ConfidenceLevel confidence;
    private final String reason;

    public DagEdge(String producerNodeId,
                   String consumerNodeId,
                   String parameterName,
                   String sourceField,
                   ParameterLocation location,
                   ConfidenceLevel confidence,
                   String reason) {
        this.producerNodeId = producerNodeId;
        this.consumerNodeId = consumerNodeId;
        this.parameterName = parameterName != null ? parameterName : "id";
        this.sourceField = sourceField != null ? sourceField : "id";
        this.location = location != null ? location : ParameterLocation.PATH;
        this.confidence = confidence != null ? confidence : ConfidenceLevel.HIGH;
        this.reason = reason;
    }

    public String getProducerNodeId() { return producerNodeId; }
    public String getConsumerNodeId() { return consumerNodeId; }
    public String getParameterName() { return parameterName; }
    public String getSourceField() { return sourceField; }
    public ParameterLocation getLocation() { return location; }
    public ConfidenceLevel getConfidence() { return confidence; }
    public String getReason() { return reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DagEdge dagEdge = (DagEdge) o;
        return Objects.equals(producerNodeId, dagEdge.producerNodeId) &&
               Objects.equals(consumerNodeId, dagEdge.consumerNodeId) &&
               Objects.equals(parameterName, dagEdge.parameterName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(producerNodeId, consumerNodeId, parameterName);
    }

    @Override
    public String toString() {
        return "DagEdge{" +
                "producer='" + producerNodeId + '\'' +
                " -> consumer='" + consumerNodeId + '\'' +
                ", param='" + parameterName + '\'' +
                ", loc=" + location +
                '}';
    }
}
