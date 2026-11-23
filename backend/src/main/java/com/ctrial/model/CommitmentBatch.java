package com.ctrial.model;

import com.ctrial.model.Enums.LeafState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 可追加承诺树批次. Signing hashes are folded into an append-only Merkle-style
 * tree per day or per centre, so a large batch can prove that one material
 * already existed at a point in time. A revocation or an attachment re-upload
 * does not rewrite the leaf: the leaf keeps the original hash and gets an
 * explicit {@link LeafState} plus an extra index row explaining the state.
 */
@Entity
@Table(name = "commitment_batch", indexes = {
        @Index(name = "idx_batch_scope", columnList = "site_code,bucket_day")
})
@Getter
@Setter
@NoArgsConstructor
public class CommitmentBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    /** Folding bucket: a study day (per-day fold) or a site-wide label. */
    @Column(name = "bucket_day", nullable = false)
    private int bucketDay;

    @Column(name = "folded_at", nullable = false)
    private Instant foldedAt;

    @Column(name = "leaf_count", nullable = false)
    private int leafCount;

    /** Append-only root hash; later leaf-state changes are indexed, never folded away. */
    @Column(name = "root_hash", nullable = false, length = 64)
    private String rootHash;

    /** Ordered leaf lines "signatureNo:hash:leafState" joined by newline. */
    @Column(name = "leaf_index", nullable = false, columnDefinition = "text")
    private String leafIndex;

    /** Extra index explaining non-active leaves (revocation / re-upload), "signatureNo=reason" lines. */
    @Column(name = "leaf_state_index", columnDefinition = "text")
    private String leafStateIndex;

    @Column(name = "sealed", nullable = false)
    private boolean sealed;
}
