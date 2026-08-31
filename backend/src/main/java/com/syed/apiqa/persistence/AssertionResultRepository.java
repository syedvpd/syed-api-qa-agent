package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.AssertionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AssertionResultRepository extends JpaRepository<AssertionResult, String> {
    List<AssertionResult> findByExecutionId(String executionId);
}
