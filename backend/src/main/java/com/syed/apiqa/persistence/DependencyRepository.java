package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.Dependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DependencyRepository extends JpaRepository<Dependency, String> {
    List<Dependency> findByTestRunId(String testRunId);
}
