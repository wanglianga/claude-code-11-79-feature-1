package com.ctrial.repo;

import com.ctrial.model.ProofConclusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProofConclusionRepo extends JpaRepository<ProofConclusion, Long> {
    List<ProofConclusion> findBySignatureNoOrderByVersionNoAscIdAsc(String signatureNo);

    Optional<ProofConclusion> findFirstBySignatureNoAndSupersededFalseOrderByIdDesc(String signatureNo);

    List<ProofConclusion> findBySupersededFalseOrderByCompiledAtDesc();
}
