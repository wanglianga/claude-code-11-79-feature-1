package com.ctrial.repo;

import com.ctrial.model.SamplingResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SamplingResultRepo extends JpaRepository<SamplingResult, Long> {
    Optional<SamplingResult> findByCode(String code);

    List<SamplingResult> findAllByOrderByRunAtDesc();

    List<SamplingResult> findByPlanCodeOrderByRunAtDesc(String planCode);
}
