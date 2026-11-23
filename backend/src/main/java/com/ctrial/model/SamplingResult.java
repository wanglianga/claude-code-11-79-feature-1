package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 抽检结果 — execution of a {@link SamplingPlan}. When the sample shows that a
 * device batch's integrity statements have failed, the result carries the
 * widened uncertainty scope and references the original audit conclusion
 * version that must be preserved (never overwritten).
 */
@Entity
@Table(name = "sampling_result", indexes = {
        @Index(name = "idx_result_plan", columnList = "plan_code")
})
@Getter
@Setter
@NoArgsConstructor
public class SamplingResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "plan_code", nullable = false)
    private String planCode;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "device_batch")
    private String deviceBatch;

    @Column(name = "reviewed_count", nullable = false)
    private int reviewedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "distortions_observed", length = 500)
    private String distortionsObserved;

    @Column(name = "attestation_failure", nullable = false)
    private boolean attestationFailure;

    /** Signatures whose proof uncertainty was widened after a batch attestation failure. */
    @Column(name = "widened_signature_nos", length = 1000)
    private String widenedSignatureNos;

    /**
     * Prior proof-conclusion versions retained for the widened signatures,
     * as "signatureNo:conclusionId" pairs — the original audit conclusion is kept.
     */
    @Column(name = "retained_conclusion_refs", length = 1000)
    private String retainedConclusionRefs;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "run_by", nullable = false)
    private String runBy;

    @Column(name = "summary", nullable = false, length = 1000)
    private String summary;
}
