package com.ctrial.repo;

import com.ctrial.model.AttachmentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentVersionRepo extends JpaRepository<AttachmentVersion, Long> {
    List<AttachmentVersion> findBySignatureNoOrderByVersionNoAsc(String signatureNo);
}
