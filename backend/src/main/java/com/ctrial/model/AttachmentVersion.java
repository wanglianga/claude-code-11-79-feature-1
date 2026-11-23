package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One immutable attachment version under a signature number. Re-uploads append a
 * new row; the original row is never overwritten, so a later hash can always be
 * compared against what was first witnessed. The certificate-chain thumbprints
 * captured at upload let the diff-er distinguish a continuous chain from a break.
 */
@Entity
@Table(name = "attachment_version", indexes = {
        @Index(name = "idx_attachment_signature", columnList = "signature_no,version_no")
})
@Getter
@Setter
@NoArgsConstructor
public class AttachmentVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signature_no", nullable = false)
    private String signatureNo;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 64)
    private String hash;

    /** Chain thumbprints observed when this version was uploaded. */
    @Column(name = "cert_chain_thumbprints", nullable = false, length = 500)
    private String certChainThumbprints;

    @Column(name = "signer_cert_thumbprint", nullable = false, length = 64)
    private String signerCertThumbprint;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** Local actor handle that produced the upload (nurse补传 / device sync). */
    @Column(name = "uploaded_by_ref", nullable = false)
    private String uploadedByRef;

    /** Free-text supplement explanation; required for a hash change to count as legitimate. */
    @Column(name = "supplement_note", length = 1000)
    private String supplementNote;

    @Column(length = 500)
    private String note;
}
