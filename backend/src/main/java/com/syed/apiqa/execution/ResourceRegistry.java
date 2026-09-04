package com.syed.apiqa.execution;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, run-scoped Resource Registry.
 * Captures created entity IDs and properties from successful POST/PUT creations
 * and injects real parent entity IDs into dependent request bodies and path parameters.
 */
public class ResourceRegistry implements Serializable {

    public static class ResourceEntry implements Serializable {
        private final String entityType;
        private final Object id;
        private final Map<String, Object> attributes;
        private final long createdAtMs;

        public ResourceEntry(String entityType, Object id, Map<String, Object> attributes) {
            this.entityType = entityType;
            this.id = id;
            this.attributes = attributes != null ? Map.copyOf(attributes) : Collections.emptyMap();
            this.createdAtMs = System.currentTimeMillis();
        }

        public String getEntityType() { return entityType; }
        public Object getId() { return id; }
        public Map<String, Object> getAttributes() { return attributes; }
        public long getCreatedAtMs() { return createdAtMs; }
    }

    private final Map<String, List<ResourceEntry>> registry = new ConcurrentHashMap<>();

    public void registerCreatedResource(String entityType, Object id, Map<String, Object> attributes) {
        if (entityType == null || entityType.isBlank() || id == null) {
            return;
        }

        String cleanType = normalizeEntityType(entityType);
        ResourceEntry entry = new ResourceEntry(cleanType, id, attributes);
        registry.computeIfAbsent(cleanType, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    public Object getLatestId(String entityType) {
        if (entityType == null) return null;
        String cleanType = normalizeEntityType(entityType);
        List<ResourceEntry> list = registry.get(cleanType);
        if (list != null && !list.isEmpty()) {
            return list.get(list.size() - 1).getId();
        }
        return null;
    }

    public Object findMatchingValue(String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return null;
        }

        String cleanProp = propertyName.toLowerCase().trim();

        // 1. Direct match on property name
        Object directVal = getLatestId(cleanProp);
        if (directVal != null) return directVal;

        // 2. Strip _id or Id suffix (e.g. user_id -> user, agency_id -> agency, customerId -> customer)
        if (cleanProp.endsWith("_id") || cleanProp.endsWith("id")) {
            String entityCandidate = cleanProp.replaceAll("(_id|id)$", "");
            Object candidateVal = getLatestId(entityCandidate);
            if (candidateVal != null) return candidateVal;
        }

        // 3. Plural stripping (e.g. assigned_projects -> project, users -> user)
        if (cleanProp.endsWith("s")) {
            String singularCandidate = cleanProp.substring(0, cleanProp.length() - 1);
            Object candidateVal = getLatestId(singularCandidate);
            if (candidateVal != null) return candidateVal;
        }

        // 4. Search across registered entity attributes
        for (Map.Entry<String, List<ResourceEntry>> entry : registry.entrySet()) {
            List<ResourceEntry> entries = entry.getValue();
            if (!entries.isEmpty()) {
                ResourceEntry last = entries.get(entries.size() - 1);
                if (cleanProp.equalsIgnoreCase(entry.getKey())) {
                    return last.getId();
                }
                if (last.getAttributes().containsKey(cleanProp)) {
                    return last.getAttributes().get(cleanProp);
                }
            }
        }

        return null;
    }

    public Map<String, List<ResourceEntry>> getAllEntries() {
        return Collections.unmodifiableMap(registry);
    }

    private String normalizeEntityType(String entityType) {
        String clean = entityType.trim().toLowerCase();
        if (clean.endsWith("s") && clean.length() > 3) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}
