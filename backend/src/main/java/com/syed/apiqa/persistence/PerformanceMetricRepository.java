package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.PerformanceMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PerformanceMetricRepository extends JpaRepository<PerformanceMetric, String> {
    List<PerformanceMetric> findByTestRunId(String testRunId);
    Optional<PerformanceMetric> findByTestRunIdAndApiEndpointId(String testRunId, String apiEndpointId);
    Optional<PerformanceMetric> findByTestRunIdAndApiEndpointIsNull(String testRunId);
}
