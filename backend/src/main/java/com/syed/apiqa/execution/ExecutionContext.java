package com.syed.apiqa.execution;

import com.syed.apiqa.auth.IdentitySession;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe execution context scoped strictly to a single TestRun.
 * Stores extracted variables with full provenance and runtime type information,
 * and resolves Postman-like ${variable}, mustache {{variable}}, and OpenAPI {variable} placeholders.
 */
public class ExecutionContext {

    public enum VariableScope {
        GLOBAL,
        RUN,
        SESSION,
        RESOURCE,
        LOCAL
    }

    public static class VariableProvenance implements Serializable {
        private final String variableName;
        private final String value;
        private final String sourceEndpoint;
        private final String sourceJsonPath;
        private final String identityName;
        private final OffsetDateTime capturedAt;

        public VariableProvenance(String variableName, String value, String sourceEndpoint,
                                  String sourceJsonPath, String identityName) {
            this.variableName = variableName;
            this.value = value;
            this.sourceEndpoint = sourceEndpoint;
            this.sourceJsonPath = sourceJsonPath;
            this.identityName = identityName;
            this.capturedAt = OffsetDateTime.now();
        }

        public String getVariableName() { return variableName; }
        public String getValue() { return value; }
        public String getSourceEndpoint() { return sourceEndpoint; }
        public String getSourceJsonPath() { return sourceJsonPath; }
        public String getIdentityName() { return identityName; }
        public OffsetDateTime getCapturedAt() { return capturedAt; }
    }

    public static class RuntimeVariable implements Serializable {
        private final String name;
        private final String stringValue;
        private final VariableExtractionEngine.VariableType type;
        private final Object rawValue;
        private final boolean sensitive;
        private final VariableProvenance provenance;
        private final OffsetDateTime capturedAt;

        public RuntimeVariable(String name, String stringValue, VariableExtractionEngine.VariableType type,
                               Object rawValue, boolean sensitive, VariableProvenance provenance) {
            this.name = name;
            this.stringValue = stringValue;
            this.type = type != null ? type : VariableExtractionEngine.VariableType.STRING;
            this.rawValue = rawValue != null ? rawValue : stringValue;
            this.sensitive = sensitive;
            this.provenance = provenance;
            this.capturedAt = OffsetDateTime.now();
        }

        public String getName() { return name; }
        public String getStringValue() { return stringValue; }
        public VariableExtractionEngine.VariableType getType() { return type; }
        public Object getRawValue() { return rawValue; }
        public boolean isSensitive() { return sensitive; }
        public VariableProvenance getProvenance() { return provenance; }
        public OffsetDateTime getCapturedAt() { return capturedAt; }
    }

    private final String testRunId;
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final Map<String, RuntimeVariable> runtimeVariables = new ConcurrentHashMap<>();
    private final Map<String, VariableProvenance> provenances = new ConcurrentHashMap<>();
    private final Map<String, IdentitySession> sessions = new ConcurrentHashMap<>();
    private final ResourceRegistry resourceRegistry = new ResourceRegistry();

    private static final Pattern POSTMAN_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern MUSTACHE_VAR_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final Pattern OPENAPI_PARAM_PATTERN = Pattern.compile("(?<!\\{)\\{([a-zA-Z0-9_.-]+)\\}(?!\\})");

    public ExecutionContext(String testRunId) {
        this.testRunId = testRunId;
    }

    public ResourceRegistry getResourceRegistry() {
        return resourceRegistry;
    }

    public String getTestRunId() {
        return testRunId;
    }

    public String getRunId() {
        return testRunId;
    }

    public void setVariable(String name, String value) {
        setVariable(name, value, VariableScope.RUN, null);
    }

    public void setVariable(String name, String value, VariableScope scope, VariableProvenance provenance) {
        if (name != null && value != null) {
            String trimmedName = name.trim();
            String trimmedVal = value.trim();
            variables.put(trimmedName, trimmedVal);
            if (provenance != null) {
                provenances.put(trimmedName, provenance);
            }
            runtimeVariables.put(trimmedName, new RuntimeVariable(
                    trimmedName, trimmedVal, VariableExtractionEngine.VariableType.STRING, trimmedVal, false, provenance
            ));
        }
    }

    public void setRuntimeVariable(RuntimeVariable var) {
        if (var != null && var.getName() != null) {
            String trimmedName = var.getName().trim();
            runtimeVariables.put(trimmedName, var);
            if (var.getStringValue() != null) {
                variables.put(trimmedName, var.getStringValue());
            }
            if (var.getProvenance() != null) {
                provenances.put(trimmedName, var.getProvenance());
            }
        }
    }

    public RuntimeVariable getRuntimeVariable(String name) {
        if (name == null) return null;
        RuntimeVariable rv = runtimeVariables.get(name.trim());
        if (rv != null) return rv;

        String val = getVariable(name);
        if (val != null) {
            return runtimeVariables.get(val);
        }
        return null;
    }

    public boolean hasVariable(String name) {
        return getVariable(name) != null;
    }

    public String getVariable(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String val = variables.get(trimmed);
        if (val != null) return val;

        // Strip leading $. or $ or /
        if (trimmed.startsWith("$.") || trimmed.startsWith("/")) {
            String stripped = trimmed.replaceFirst("^(\\$\\.|/)", "");
            if (variables.containsKey(stripped)) return variables.get(stripped);
        }

        // Fallback: if name is "userId", check "user.id", "user_id", "id"
        String lower = trimmed.toLowerCase();
        if (lower.endsWith("id") && lower.length() > 2) {
            String prefix = lower.substring(0, lower.length() - 2);
            if (variables.containsKey(prefix + ".id")) return variables.get(prefix + ".id");
            if (variables.containsKey(prefix + "_id")) return variables.get(prefix + "_id");
            if (variables.containsKey(prefix + "Id")) return variables.get(prefix + "Id");
            if (variables.containsKey("id")) return variables.get("id");
        }

        // Fallback: if name is "user.id" and we have "id", or name is "id" and we have "user.id"
        if (trimmed.contains(".")) {
            String prop = trimmed.substring(trimmed.indexOf('.') + 1);
            if (variables.containsKey(prop)) return variables.get(prop);
        } else {
            // Find any key ending with .<name>
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                if (entry.getKey().endsWith("." + trimmed)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    public VariableProvenance getProvenance(String name) {
        return name != null ? provenances.get(name.trim()) : null;
    }

    public Map<String, VariableProvenance> getAllProvenances() {
        return Collections.unmodifiableMap(provenances);
    }

    public Map<String, String> getAllVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public Map<String, RuntimeVariable> getAllRuntimeVariables() {
        return Collections.unmodifiableMap(runtimeVariables);
    }

    public void registerSession(IdentitySession session) {
        if (session != null) {
            if (session.getIdentityId() != null && !session.getIdentityId().isBlank()) {
                sessions.put(session.getIdentityId(), session);
            }
            if (session.getIdentityName() != null && !session.getIdentityName().isBlank()) {
                sessions.put(session.getIdentityName(), session);
            }
        }
    }

    public IdentitySession getSession(String identityKey) {
        return identityKey != null ? sessions.get(identityKey) : null;
    }

    public Map<String, IdentitySession> getAllSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    public static class ResolutionResult {
        private final String resolvedContent;
        private final boolean fullyResolved;
        private final String missingVariable;

        public ResolutionResult(String resolvedContent, boolean fullyResolved, String missingVariable) {
            this.resolvedContent = resolvedContent;
            this.fullyResolved = fullyResolved;
            this.missingVariable = missingVariable;
        }

        public String getResolvedContent() { return resolvedContent; }
        public boolean isFullyResolved() { return fullyResolved; }
        public String getMissingVariable() { return missingVariable; }
    }

    public ResolutionResult resolve(String template) {
        if (template == null) {
            return new ResolutionResult(null, true, null);
        }

        String result = template;
        String missingVar = null;

        // 1. Resolve Postman syntax: ${variable}
        if (result.contains("${")) {
            Matcher matcher = POSTMAN_VAR_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String varName = matcher.group(1).trim();
                String val = getVariable(varName);

                if (val == null) {
                    missingVar = varName;
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(val));
                }
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }

        // 2. Resolve Double Braces: {{var}}
        if (result.contains("{{")) {
            Matcher matcher = MUSTACHE_VAR_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String varName = matcher.group(1).trim();
                String val = getVariable(varName);

                if (val == null) {
                    if (missingVar == null) missingVar = varName;
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(val));
                }
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }

        // 3. Resolve Single Braces: {param} (Standard OpenAPI path syntax)
        if (result.contains("{")) {
            Matcher matcher = OPENAPI_PARAM_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String varName = matcher.group(1).trim();
                String val = getVariable(varName);

                if (val == null) {
                    if (missingVar == null) missingVar = varName;
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(val));
                }
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }

        boolean fullyResolved = (missingVar == null);
        return new ResolutionResult(result, fullyResolved, missingVar);
    }
}
