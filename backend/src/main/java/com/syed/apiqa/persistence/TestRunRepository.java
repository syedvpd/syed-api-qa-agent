package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TestRunRepository extends JpaRepository<TestRun, String> {
    List<TestRun> findByOrderByCreatedAtDesc();
    java.util.Optional<TestRun> findByIdempotencyKey(String idempotencyKey);
}
