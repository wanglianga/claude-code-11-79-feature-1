package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 抽检计划 — the minimum-review sample computed per centre/device model/
 * anomaly pattern, together with the distortion family the sample is expected
 * to distinguish. A plan is immutable once issued; executing it produces a
 * {@link SamplingResult}. When a device batch's integrity statement fails, the
 * planner re-issues a plan with a widened uncertainty scope instead of
 * overwriting this one.
 */
@Entity
@Table(name = "sampling_plan", indexes = {
        @Index(name = "idx_plan_scope", columnList = "site_code,device_model,active")
})
@Getter
@Setter
@NoArgsConstructor
public class SamplingPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    /** "*" = every device model at the site; otherwise a concrete model/batch. */
    @Column(name = "device_model", nullable = false)
    private String deviceModel;

    @Column(name = "device_batch")
    private String deviceBatch;

    /** Anomaly pattern this plan reacts to, e.g. HASH_SUBSTITUTION / BATCH_ATTESTATION_FAILURE. */
    @Column(name = "anomaly_pattern", nullable = false)
    private String anomalyPattern;

    @Column(name = "population_size", nullable = false)
    private int populationSize;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    /** Distortion types the sample is statistically expected to separate. */
    @Column(name = "expected_distortions", nullable = false, length = 500)
    private String expectedDistortions;

    /** Confidence level the sample size was derived for, e.g. 0.95. */
    @Column(name = "confidence_level", nullable = false)
    private double confidenceLevel;

    /** Material signatures inside the widened uncertainty range (batch failure case). */
    @Column(name = "uncertainty_scope", length = 1000)
    private String uncertaintyScope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private boolean active;

    /** Set when superseded by a widened-scope re-plan. */
    @Column(name = "superseded_by")
    private String supersededBy;

    @Column(name = "plan_digest", nullable = false, length = 64)
    private String planDigest;
}
