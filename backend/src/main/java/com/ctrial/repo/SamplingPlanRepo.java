package com.ctrial.repo;

import com.ctrial.model.SamplingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SamplingPlanRepo extends JpaRepository<SamplingPlan, Long> {
    Optional<SamplingPlan> findByCode(String code);

    List<SamplingPlan> findByActiveTrueOrderByCreatedAtDesc();

    List<SamplingPlan> findAllByOrderByCreatedAtDesc();

    List<SamplingPlan> findBySiteCodeAndActiveTrueOrderByCreatedAtDesc(String siteCode);
}
