package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.EndpointCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EndpointCoverageRepository extends JpaRepository<EndpointCoverage, String> {

    List<EndpointCoverage> findByTestRunIdOrderByPathAsc(String testRunId);

    void deleteByTestRunId(String testRunId);
}
