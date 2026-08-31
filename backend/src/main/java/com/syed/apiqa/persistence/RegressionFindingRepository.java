package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.RegressionFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegressionFindingRepository extends JpaRepository<RegressionFinding, String> {

    List<RegressionFinding> findByTestRunIdOrderByCreatedAtDesc(String testRunId);

    List<RegressionFinding> findByTestRunIdAndBaselineRunId(String testRunId, String baselineRunId);

    @Modifying
    @Query("DELETE FROM RegressionFinding rf WHERE rf.testRun.id = :testRunId")
    void deleteByTestRunId(@Param("testRunId") String testRunId);
}
