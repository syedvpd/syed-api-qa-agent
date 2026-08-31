package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.ApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, String> {
    List<ApiEndpoint> findByTestRunId(String testRunId);
}
