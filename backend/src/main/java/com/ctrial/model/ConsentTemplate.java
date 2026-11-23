package com.ctrial.model;

import com.ctrial.model.Enums.TemplateStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 模板登记簿 (template registry). One row per publication interval of a consent
 * template, keyed by centre, language, subject age band and the protocol revision
 * it belongs to. A template replacement never overwrites a row: it closes the
 * previous interval ({@code publishedTo}) and opens a new one, and a revocation is
 * recorded as an explicit status with timestamp rather than by deleting history.
 */
@Entity
@Table(name = "consent_template", indexes = {
        @Index(name = "idx_template_lookup", columnList = "site_code,language,age_band,protocol_version,published_from")
})
@Getter
@Setter
@NoArgsConstructor
public class ConsentTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Centre this interval applies to; "*" marks a study-wide template. */
    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String language;

    /** Subject age band, e.g. ADULT / ADOLESCENT / CHILD. */
    @Column(name = "age_band", nullable = false)
    private String ageBand;

    /** Protocol revision this template text was issued under. */
    @Column(name = "protocol_version", nullable = false)
    private String protocolVersion;

    @Column(nullable = false)
    private String title;

    /** Half-open publication interval [publishedFrom, publishedTo). */
    @Column(name = "published_from", nullable = false)
    private Instant publishedFrom;

    /** Null while the interval is still open. */
    @Column(name = "published_to")
    private Instant publishedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Digest of the canonical field list; the field summary itself is kept below. */
    @Column(name = "field_summary_hash", nullable = false, length = 64)
    private String fieldSummaryHash;

    /** Canonical, ordered summary of the fields the template captures. */
    @Column(name = "field_summary", nullable = false, length = 1000)
    private String fieldSummary;

    @Column(length = 1000)
    private String note;
}
