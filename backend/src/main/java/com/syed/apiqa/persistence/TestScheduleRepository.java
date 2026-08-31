package com.syed.apiqa.persistence;

import com.syed.apiqa.domain.TestSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestScheduleRepository extends JpaRepository<TestSchedule, String> {

    List<TestSchedule> findByOrderByCreatedAtDesc();

    List<TestSchedule> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<TestSchedule> findByEnabledTrue();
}
