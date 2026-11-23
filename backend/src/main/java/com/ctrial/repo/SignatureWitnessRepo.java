package com.ctrial.repo;

import com.ctrial.model.SignatureWitness;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignatureWitnessRepo extends JpaRepository<SignatureWitness, Long> {
    Optional<SignatureWitness> findBySignatureNo(String signatureNo);

    List<SignatureWitness> findAllByOrderBySignedAtAscSignatureNoAsc();

    List<SignatureWitness> findBySiteCodeOrderBySignedAtAsc(String siteCode);

    List<SignatureWitness> findByDeviceBatch(String deviceBatch);

    List<SignatureWitness> findByGuardianProofHash(String guardianProofHash);

    List<SignatureWitness> findByCommitmentBatchCode(String batchCode);
}
