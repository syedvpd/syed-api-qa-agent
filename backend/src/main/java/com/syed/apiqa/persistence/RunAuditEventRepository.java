package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.RunAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RunAuditEventRepository extends JpaRepository<RunAuditEvent, String> {
    List<RunAuditEvent> findByTestRunIdOrderByCreatedAtAsc(String testRunId);
}
