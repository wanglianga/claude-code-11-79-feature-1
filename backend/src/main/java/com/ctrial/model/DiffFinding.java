package com.ctrial.model;

import com.ctrial.model.Enums.DiffKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 篡改差异器 finding for one signature number: the result of comparing every
 * attachment version, its hash and the certificate chain. Findings are
 * append-only; a re-evaluation inserts a new row and supersedes the old one.
 */
@Entity
@Table(name = "diff_finding", indexes = {
        @Index(name = "idx_diff_signature", columnList = "signature_no,superseded"),
        @Index(name = "idx_diff_kind", columnList = "kind")
})
@Getter
@Setter
@NoArgsConstructor
public class DiffFinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signature_no", nullable = false)
    private String signatureNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiffKind kind;

    /** Version numbers being compared, e.g. "1->2". */
    @Column(name = "version_pair", nullable = false, length = 50)
    private String versionPair;

    @Column(name = "hash_changed", nullable = false)
    private boolean hashChanged;

    @Column(name = "chain_continuous", nullable = false)
    private boolean chainContinuous;

    @Column(name = "supplement_note_present", nullable = false)
    private boolean supplementNotePresent;

    /** The two hashes side by side for the UI. */
    @Column(name = "prior_hash", length = 64)
    private String priorHash;

    @Column(name = "current_hash", length = 64)
    private String currentHash;

    /** For identity-link anomalies: the two pseudonyms sharing guardianship evidence. */
    @Column(name = "subject_pseudonyms", length = 200)
    private String subjectPseudonyms;

    @Column(name = "guardian_proof_ref")
    private String guardianProofRef;

    @Column(name = "detail", nullable = false, length = 1000)
    private String detail;

    @Column(name = "found_at", nullable = false)
    private Instant foundAt;

    @Column(name = "superseded", nullable = false)
    private boolean superseded;

    @Column(name = "finding_digest", nullable = false, length = 64)
    private String findingDigest;
}
