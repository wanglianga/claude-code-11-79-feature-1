package com.ctrial.model;

import com.ctrial.model.Enums.AttestationState;
import com.ctrial.model.Enums.CommitmentScheme;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 签署见证账 (signature witness ledger). One row per witnessed electronic
 * signature event. It holds only local-domain evidence: certificate
 * identifiers/digests (never certificate identity plaintext beyond a local
 * handle), the terminal integrity statement, the three distinct clocks
 * (signed / witnessed / uploaded), the attachment hash, nurse review and the
 * guardianship proof reference. Subject plaintext identity is never stored
 * here — only the pseudonym and the local identity handle, which never leaves
 * the residency domain.
 */
@Entity
@Table(name = "signature_witness", indexes = {
        @Index(name = "idx_witness_signature_no", columnList = "signature_no", unique = true),
        @Index(name = "idx_witness_subject", columnList = "subject_pseudonym,signed_at"),
        @Index(name = "idx_witness_device", columnList = "device_model,device_batch"),
        @Index(name = "idx_witness_guardian", columnList = "guardian_proof_ref")
})
@Getter
@Setter
@NoArgsConstructor
public class SignatureWitness {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable business identifier of the signature; versions hang off it. */
    @Column(name = "signature_no", nullable = false, unique = true)
    private String signatureNo;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    /** Local-only identity handle. Never projected outside the residency domain. */
    @Column(name = "local_identity_handle", nullable = false)
    private String localIdentityHandle;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "template_code", nullable = false)
    private String templateCode;

    /** Local-domain e-signature certificate serial/handle (no transmitted plaintext identity). */
    @Column(name = "signer_cert_ref", nullable = false)
    private String signerCertRef;

    /** Thumbprint of the signer certificate as observed in the local domain. */
    @Column(name = "signer_cert_thumbprint", nullable = false, length = 64)
    private String signerCertThumbprint;

    /** Issuer chain anchors observed locally, newest-first, thumbprints joined by comma. */
    @Column(name = "cert_chain_thumbprints", nullable = false, length = 500)
    private String certChainThumbprints;

    @Column(name = "device_model", nullable = false)
    private String deviceModel;

    @Column(name = "device_batch", nullable = false)
    private String deviceBatch;

    @Column(name = "device_id_ref", nullable = false)
    private String deviceIdRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "attestation_state", nullable = false)
    private AttestationState attestationState;

    /** Raw terminal integrity statement digest. */
    @Column(name = "attestation_digest", nullable = false, length = 64)
    private String attestationDigest;

    /** Local signature instant claimed by the tablet (may be offline). */
    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    /** Instant the local witness (second signer / device watchdog) countersigned. */
    @Column(name = "witnessed_at", nullable = false)
    private Instant witnessedAt;

    /** Instant the study nurse's补传 reached the platform. Never back-fills signedAt. */
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** Trusted clock source for signedAt; when later resynced, the compiler must pend. */
    @Column(name = "trusted_time_at")
    private Instant trustedTimeAt;

    @Column(name = "trusted_time_resynced", nullable = false)
    private boolean trustedTimeResynced;

    /** Hash of the first attachment version; duplicated on AttachmentVersion rows. */
    @Column(name = "attachment_hash", nullable = false, length = 64)
    private String attachmentHash;

    @Column(name = "nurse_reviewed", nullable = false)
    private boolean nurseReviewed;

    @Column(name = "nurse_review_ref")
    private String nurseReviewRef;

    /** Reference to the guardianship proof document; null for adult self-signatures. */
    @Column(name = "guardian_proof_ref")
    private String guardianProofRef;

    /** Digest of the guardianship proof, so sharing/overlap can be detected without plaintext. */
    @Column(name = "guardian_proof_hash", length = 64)
    private String guardianProofHash;

    /**
     * Whether any local witness evidence exists at all. A material lacking local
     * witness is never auto-downgraded to withdrawal or to valid consent.
     */
    @Column(name = "local_witness_present", nullable = false)
    private boolean localWitnessPresent;

    /** Randomisation/enrolment instant used by the compiler to order signature vs enrolment. */
    @Column(name = "randomized_at")
    private Instant randomizedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "commitment_scheme", nullable = false)
    private CommitmentScheme commitmentScheme;

    /** Batch this signature's leaf was folded into (tree scheme), null before folding. */
    @Column(name = "commitment_batch_code")
    private String commitmentBatchCode;

    @Column(length = 1000)
    private String note;
}
