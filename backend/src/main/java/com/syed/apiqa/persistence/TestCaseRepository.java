package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, String> {
    List<TestCase> findByTestRunIdOrderByExecutionOrderAsc(String testRunId);
}
