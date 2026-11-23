package com.ctrial.model;

import com.ctrial.model.Enums.ProofStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 本地域核验结论 — the compiler's verdict for one signature witness.
 *
 * <p>Conclusions are append-only and versioned: when new evidence (a re-upload,
 * a revoked device batch, a resolved identity link) changes the verdict, a new
 * row is inserted and the previous row is retained as the prior audit
 * conclusion. Nothing mutates the historical conclusion.
 */
@Entity
@Table(name = "proof_conclusion", indexes = {
        @Index(name = "idx_conclusion_signature", columnList = "signature_no,version_no"),
        @Index(name = "idx_conclusion_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class ProofConclusion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signature_no", nullable = false)
    private String signatureNo;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProofStatus status;

    /** Human-readable reason codes, each backed by one entry in evidenceRefs. */
    @Column(name = "reason_codes", nullable = false, length = 500)
    private String reasonCodes;

    /**
     * The local evidence each status depends on, as canonical "key=ref" lines
     * (template interval id, witness id, attachment version id, cert thumbprint,
     * attestation, clock source). Plaintext identity never appears here.
     */
    @Column(name = "evidence_refs", nullable = false, columnDefinition = "text")
    private String evidenceRefs;

    @Column(name = "compiled_at", nullable = false)
    private Instant compiledAt;

    @Column(name = "compiled_by", nullable = false)
    private String compiledBy;

    /** Digest of the canonical conclusion payload, used to prove immutability. */
    @Column(name = "conclusion_digest", nullable = false, length = 64)
    private String conclusionDigest;

    /** Set when this row was superseded by a newer conclusion version. */
    @Column(name = "superseded", nullable = false)
    private boolean superseded;
}
