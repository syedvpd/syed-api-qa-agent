package com.syed.apiqa.domain.canonical;

import java.io.Serializable;
import java.util.*;

/**
 * Universal Canonical API Model.
 * Represents the complete, dereferenced contract of any deployed API without project-specific assumptions.
 */
public class CanonicalApiModel implements Serializable {

    private CanonicalMetadata metadata = new CanonicalMetadata();
    private List<CanonicalServer> servers = new ArrayList<>();
    private List<CanonicalOperation> operations = new ArrayList<>();
    private Map<String, Object> schemas = new LinkedHashMap<>();
    private Map<String, CanonicalSecurityScheme> securitySchemes = new LinkedHashMap<>();
    private Map<String, CanonicalResource> resources = new LinkedHashMap<>();
    private List<CanonicalWorkflow> candidateWorkflows = new ArrayList<>();

    public CanonicalMetadata getMetadata() { return metadata; }
    public void setMetadata(CanonicalMetadata metadata) { this.metadata = metadata; }

    public List<CanonicalServer> getServers() { return servers; }
    public void setServers(List<CanonicalServer> servers) { this.servers = servers; }

    public List<CanonicalOperation> getOperations() { return operations; }
    public void setOperations(List<CanonicalOperation> operations) { this.operations = operations; }

    public Map<String, Object> getSchemas() { return schemas; }
    public void setSchemas(Map<String, Object> schemas) { this.schemas = schemas; }

    public Map<String, CanonicalSecurityScheme> getSecuritySchemes() { return securitySchemes; }
    public void setSecuritySchemes(Map<String, CanonicalSecurityScheme> securitySchemes) { this.securitySchemes = securitySchemes; }

    public Map<String, CanonicalResource> getResources() { return resources; }
    public void setResources(Map<String, CanonicalResource> resources) { this.resources = resources; }

    public List<CanonicalWorkflow> getCandidateWorkflows() { return candidateWorkflows; }
    public void setCandidateWorkflows(List<CanonicalWorkflow> candidateWorkflows) { this.candidateWorkflows = candidateWorkflows; }

    // --- Inner canonical definitions ---

    public static class CanonicalMetadata implements Serializable {
        private String title;
        private String version;
        private String description;
        private String targetBaseUrl;
        private String specSourceUrl;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTargetBaseUrl() { return targetBaseUrl; }
        public void setTargetBaseUrl(String targetBaseUrl) { this.targetBaseUrl = targetBaseUrl; }
        public String getSpecSourceUrl() { return specSourceUrl; }
        public void setSpecSourceUrl(String specSourceUrl) { this.specSourceUrl = specSourceUrl; }
    }

    public static class CanonicalServer implements Serializable {
        private String url;
        private String description;

        public CanonicalServer() {}
        public CanonicalServer(String url, String description) {
            this.url = url;
            this.description = description;
        }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class CanonicalOperation implements Serializable {
        private String operationId;
        private String path;
        private String method;
        private String summary;
        private String description;
        private List<String> tags = new ArrayList<>();
        private List<CanonicalParameter> parameters = new ArrayList<>();
        private CanonicalRequestBody requestBody;
        private Map<String, CanonicalResponse> responses = new LinkedHashMap<>();
        private List<Map<String, List<String>>> securityRequirements = new ArrayList<>();
        private String riskClassification = "UNKNOWN"; // READ, WRITE, DELETE, AUTH, ADMIN, BULK

        public String getOperationId() { return operationId; }
        public void setOperationId(String operationId) { this.operationId = operationId; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<CanonicalParameter> getParameters() { return parameters; }
        public void setParameters(List<CanonicalParameter> parameters) { this.parameters = parameters; }
        public CanonicalRequestBody getRequestBody() { return requestBody; }
        public void setRequestBody(CanonicalRequestBody requestBody) { this.requestBody = requestBody; }
        public Map<String, CanonicalResponse> getResponses() { return responses; }
        public void setResponses(Map<String, CanonicalResponse> responses) { this.responses = responses; }
        public List<Map<String, List<String>>> getSecurityRequirements() { return securityRequirements; }
        public void setSecurityRequirements(List<Map<String, List<String>>> securityRequirements) { this.securityRequirements = securityRequirements; }
        public String getRiskClassification() { return riskClassification; }
        public void setRiskClassification(String riskClassification) { this.riskClassification = riskClassification; }
    }

    public static class CanonicalParameter implements Serializable {
        private String name;
        private String in; // path, query, header, cookie
        private boolean required;
        private String type;
        private Object schema;
        private Object example;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIn() { return in; }
        public void setIn(String in) { this.in = in; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Object getSchema() { return schema; }
        public void setSchema(Object schema) { this.schema = schema; }
        public Object getExample() { return example; }
        public void setExample(Object example) { this.example = example; }
    }

    public static class CanonicalRequestBody implements Serializable {
        private boolean required;
        private List<String> mediaTypes = new ArrayList<>();
        private Object schema;
        private Object example;

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public List<String> getMediaTypes() { return mediaTypes; }
        public void setMediaTypes(List<String> mediaTypes) { this.mediaTypes = mediaTypes; }
        public Object getSchema() { return schema; }
        public void setSchema(Object schema) { this.schema = schema; }
        public Object getExample() { return example; }
        public void setExample(Object example) { this.example = example; }
    }

    public static class CanonicalResponse implements Serializable {
        private String statusCode;
        private String description;
        private List<String> mediaTypes = new ArrayList<>();
        private Object schema;

        public String getStatusCode() { return statusCode; }
        public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getMediaTypes() { return mediaTypes; }
        public void setMediaTypes(List<String> mediaTypes) { this.mediaTypes = mediaTypes; }
        public Object getSchema() { return schema; }
        public void setSchema(Object schema) { this.schema = schema; }
    }

    public static class CanonicalSecurityScheme implements Serializable {
        private String name;
        private String type; // http, apiKey, oauth2, openIdConnect
        private String scheme; // bearer, basic
        private String bearerFormat; // JWT
        private String in; // header, query, cookie
        private String parameterName;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getScheme() { return scheme; }
        public void setScheme(String scheme) { this.scheme = scheme; }
        public String getBearerFormat() { return bearerFormat; }
        public void setBearerFormat(String bearerFormat) { this.bearerFormat = bearerFormat; }
        public String getIn() { return in; }
        public void setIn(String in) { this.in = in; }
        public String getParameterName() { return parameterName; }
        public void setParameterName(String parameterName) { this.parameterName = parameterName; }
    }

    public static class CanonicalResource implements Serializable {
        private String name;
        private String basePath;
        private List<String> candidateIdentifiers = new ArrayList<>();
        private List<String> operations = new ArrayList<>(); // operationIds
        private String parentResource;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public List<String> getCandidateIdentifiers() { return candidateIdentifiers; }
        public void setCandidateIdentifiers(List<String> candidateIdentifiers) { this.candidateIdentifiers = candidateIdentifiers; }
        public List<String> getOperations() { return operations; }
        public void setOperations(List<String> operations) { this.operations = operations; }
        public String getParentResource() { return parentResource; }
        public void setParentResource(String parentResource) { this.parentResource = parentResource; }
    }

    public static class CanonicalWorkflow implements Serializable {
        private String name;
        private List<String> candidateStates = new ArrayList<>();
        private List<String> orderedOperationIds = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getCandidateStates() { return candidateStates; }
        public void setCandidateStates(List<String> candidateStates) { this.candidateStates = candidateStates; }
        public List<String> getOrderedOperationIds() { return orderedOperationIds; }
        public void setOrderedOperationIds(List<String> orderedOperationIds) { this.orderedOperationIds = orderedOperationIds; }
    }
}
