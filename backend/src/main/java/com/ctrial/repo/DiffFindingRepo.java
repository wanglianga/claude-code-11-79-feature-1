package com.ctrial.repo;

import com.ctrial.model.DiffFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiffFindingRepo extends JpaRepository<DiffFinding, Long> {
    List<DiffFinding> findBySignatureNoOrderByIdAsc(String signatureNo);

    List<DiffFinding> findBySupersededFalseOrderByFoundAtDesc();

    List<DiffFinding> findAllByOrderByFoundAtDesc();
}
