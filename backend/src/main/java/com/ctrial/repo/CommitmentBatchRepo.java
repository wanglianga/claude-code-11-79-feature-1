package com.ctrial.repo;

import com.ctrial.model.CommitmentBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommitmentBatchRepo extends JpaRepository<CommitmentBatch, Long> {
    Optional<CommitmentBatch> findByCode(String code);

    List<CommitmentBatch> findBySiteCodeOrderByBucketDayAsc(String siteCode);

    List<CommitmentBatch> findAllByOrderByFoldedAtDesc();
}
