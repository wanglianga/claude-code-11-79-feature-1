package com.ctrial.model;

import com.ctrial.model.Enums.WitnessRelation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 逐份交叉签名与见证关系图 edge. Connects a signature to its guardian proof,
 * signing terminal or reviewing nurse, so a break in any of those three
 * relations can be traced per document. Only pseudonyms/handles are stored —
 * no plaintext identity.
 */
@Entity
@Table(name = "witness_graph_edge", indexes = {
        @Index(name = "idx_edge_signature", columnList = "signature_no"),
        @Index(name = "idx_edge_target", columnList = "target_ref,relation")
})
@Getter
@Setter
@NoArgsConstructor
public class WitnessGraphEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signature_no", nullable = false)
    private String signatureNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WitnessRelation relation;

    /** Guardian proof ref / device id ref / nurse review ref. */
    @Column(name = "target_ref", nullable = false)
    private String targetRef;

    @Column(name = "target_digest", length = 64)
    private String targetDigest;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** A broken edge (e.g. revoked device, missing nurse review) is flagged, never deleted. */
    @Column(name = "broken", nullable = false)
    private boolean broken;

    @Column(name = "break_reason", length = 500)
    private String breakReason;
}
