package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.SpecificationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpecificationSnapshotRepository extends JpaRepository<SpecificationSnapshot, String> {
    Optional<SpecificationSnapshot> findByTestRunId(String testRunId);
}
