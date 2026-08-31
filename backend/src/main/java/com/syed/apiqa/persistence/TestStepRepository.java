package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.TestStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TestStepRepository extends JpaRepository<TestStep, String> {
    List<TestStep> findByTestCaseIdOrderByStepOrderAsc(String testCaseId);
    List<TestStep> findByTestCaseTestRunId(String testRunId);
}
