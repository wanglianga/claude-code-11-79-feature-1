package com.ctrial.repo;

import com.ctrial.model.WitnessGraphEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WitnessGraphEdgeRepo extends JpaRepository<WitnessGraphEdge, Long> {
    List<WitnessGraphEdge> findBySignatureNoOrderByRelationAscIdAsc(String signatureNo);

    List<WitnessGraphEdge> findByTargetRefAndBrokenTrue(String targetRef);

    List<WitnessGraphEdge> findAllByOrderByObservedAtAsc();
}
