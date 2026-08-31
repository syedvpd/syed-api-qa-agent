package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.CleanupRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CleanupRecordRepository extends JpaRepository<CleanupRecord, String> {
    List<CleanupRecord> findByTestRunIdOrderByExecutionOrderDesc(String testRunId);
    List<CleanupRecord> findByTestRunId(String testRunId);
}
