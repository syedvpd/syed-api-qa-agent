package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.CapturedVariable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CapturedVariableRepository extends JpaRepository<CapturedVariable, String> {
    List<CapturedVariable> findByTestRunId(String testRunId);
}
