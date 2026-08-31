package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.Execution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, String> {
    List<Execution> findByTestStepId(String testStepId);

    @Query("SELECT e FROM Execution e JOIN FETCH e.testStep ts LEFT JOIN FETCH ts.apiEndpoint LEFT JOIN FETCH ts.testCase tc WHERE tc.testRun.id = :testRunId")
    List<Execution> findByTestRunId(@Param("testRunId") String testRunId);
}
