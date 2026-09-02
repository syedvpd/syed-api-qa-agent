package com.syed.apiqa.contract.schema;

/**
 * Context for schema projection.
 * Determines whether readOnly or writeOnly properties are included or excluded.
 */
public enum SchemaContext {
    REQUEST_BODY,
    RESPONSE_BODY,
    PARAMETER;

    public boolean shouldIncludeProperty(Boolean readOnly, Boolean writeOnly) {
        boolean isReadOnly = Boolean.TRUE.equals(readOnly);
        boolean isWriteOnly = Boolean.TRUE.equals(writeOnly);

        return switch (this) {
            case REQUEST_BODY -> !isReadOnly; // readOnly properties omitted from requests
            case RESPONSE_BODY -> !isWriteOnly; // writeOnly properties omitted from responses
            case PARAMETER -> !isReadOnly;
        };
    }
}
