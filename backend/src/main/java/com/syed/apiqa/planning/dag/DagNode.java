package com.syed.apiqa.planning.dag;

import com.syed.apiqa.domain.StepStatus;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a single executable node in the Directed Acyclic Graph (DAG).
 * Thread-safe lifecycle tracking with in-degree dependency counters.
 */
public class DagNode implements Serializable {

    public enum NodeStatus {
        PENDING,
        READY,
        RUNNING,
        PASSED,
        FAILED,
        BLOCKED,
        SKIPPED
    }

    private final String id;
    private final String name;
    private final String operationMethod;
    private final String operationPath;
    private final Object payload; // e.g. TestCase, TestStep, or custom task
    private volatile NodeStatus status = NodeStatus.PENDING;
    private final AtomicInteger inDegree = new AtomicInteger(0);
    private volatile String failureReason;
    private volatile OffsetDateTime startedAt;
    private volatile OffsetDateTime completedAt;

    public DagNode(String id, String name, String operationMethod, String operationPath, Object payload) {
        this.id = id;
        this.name = name;
        this.operationMethod = operationMethod;
        this.operationPath = operationPath;
        this.payload = payload;
    }

    public DagNode(String id, String name, Object payload) {
        this(id, name, "GET", "/", payload);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getOperationMethod() { return operationMethod; }
    public String getOperationPath() { return operationPath; }
    public Object getPayload() { return payload; }

    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }

    public int getInDegree() { return inDegree.get(); }
    public void setInDegree(int count) { inDegree.set(count); }
    public int decrementInDegree() { return inDegree.decrementAndGet(); }
    public int incrementInDegree() { return inDegree.incrementAndGet(); }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public boolean isTerminal() {
        return status == NodeStatus.PASSED || status == NodeStatus.FAILED ||
               status == NodeStatus.BLOCKED || status == NodeStatus.SKIPPED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DagNode dagNode = (DagNode) o;
        return Objects.equals(id, dagNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DagNode{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", status=" + status +
                ", inDegree=" + inDegree.get() +
                '}';
    }
}
