package com.ctrial.model;

public final class Enums {

    private Enums() {
    }

    /**
     * Approved processing purpose. ALL is only valid inside a consent grant,
     * never as the purpose of a query or a delivery.
     */
    public enum Purpose {
        GLOBAL_ANALYSIS,
        CHINA_LOCAL_ANALYSIS,
        LOCAL_ANALYSIS,
        SAFETY_MONITORING,
        REGULATORY_SUBMISSION,
        ALL;

        public boolean isGlobal() {
            return this == GLOBAL_ANALYSIS;
        }

        public boolean isLocal() {
            return this == CHINA_LOCAL_ANALYSIS || this == LOCAL_ANALYSIS;
        }
    }

    /** Destination / source region codes. GLOBAL = any approved region. */
    public enum Region {
        GLOBAL, EU, CN, SG, DE, TH;

        public boolean covers(String region) {
            return this == GLOBAL || name().equalsIgnoreCase(region);
        }
    }

    /** Data domains flowing through the source ledger. Each has its own watermark. */
    public enum Domain {
        VISIT, LAB, AE, IMAGING, RANDOMIZATION, DEVIATION
    }

    public enum ConsentKind {
        GRANT, WITHDRAWAL
    }

    public enum ProtocolStatus {
        DRAFT,
        PARTIALLY_APPROVED,
        APPROVED,
        WITHDRAWN
    }

    /** Per-record availability. The three outcomes required by the spec. */
    public enum Eligibility {
        LOCAL_AVAILABLE,
        GLOBAL_AVAILABLE,
        PENDING,
        /** No lawful basis in any plane; reported separately, never silently counted as zero. */
        BLOCKED
    }

    /** Finer-grained adjudication used alongside the three availability outcomes. */
    public enum Verdict {
        ELIGIBLE,
        UNDETERMINED,
        NOT_ELIGIBLE
    }

    public enum SnapshotStatus {
        VALID,
        STALE,
        AUDIT_ONLY
    }

    public enum DeliveryStatus {
        ACTIVE,
        ACCESS_REVOKED,
        DESTROYED
    }

    public enum PlaneType {
        GLOBAL,
        LOCAL
    }

    public enum RuleAction {
        RETAIN_RESTRICTED,
        DESTROY_AFTER
    }

    public enum DispositionScope {
        STUDY,
        SITE
    }

    public enum DispositionState {
        APPLIED,
        SCHEDULED,
        EXECUTED
    }

    public enum QuarantineKind {
        RACE_WITHDRAWAL_CORRECTION,
        DUPLICATE_HASH_CONFLICT,
        CROSS_DOMAIN_MISMATCH,
        WATERMARK_INCONSISTENCY
    }

    public enum QuarantineState {
        OPEN,
        RESOLVED
    }

    public enum RecoveryStatus {
        PASS,
        FINDINGS
    }

    public enum Role {
        ADMIN,
        GLOBAL_ANALYST,
        CN_ANALYST,
        AUDITOR,
        SPONSOR
    }

    // ---------------- consent material proof chain ----------------

    /**
     * The three proof statuses emitted by the consistency compiler. They describe
     * the provenance integrity of a consent material, not the legal consent flag
     * used by eligibility — a material without local witness is never silently
     * treated as a withdrawal or as a valid consent.
     */
    public enum ProofStatus {
        /** 可验证签署: every local evidence line matches. */
        VERIFIED_SIGNATURE,
        /** 材料内容破损: attachment hash / certificate chain evidence is broken. */
        MATERIAL_CORRUPTED,
        /** 身份或时间待核: identity linkage or signing instant cannot yet be adjudicated. */
        IDENTITY_OR_TIME_PENDING
    }

    /** Classification produced by the tamper diff-er for one attachment-version pair. */
    public enum DiffKind {
        /** 合法补充: hash changed, certificate chain continuous, nursing supplement note present. */
        LEGITIMATE_SUPPLEMENT,
        /** 不可验证替换: hash changed and the chain of custody cannot be verified. */
        UNVERIFIABLE_REPLACEMENT,
        /** 重复上传: identical hash re-sent under a new version number. */
        DUPLICATE_UPLOAD,
        /** 身份关联异常: two pseudonyms share guardianship evidence inside overlapping windows. */
        IDENTITY_LINK_ANOMALY
    }

    /** Lifecycle of a registered consent template's publication interval. */
    public enum TemplateStatus {
        PUBLISHED,
        SUPERSEDED,
        REVOKED
    }

    /** Device integrity-attestation state claimed by the signing terminal. */
    public enum AttestationState {
        TRUSTED,
        /** Trusted time exists but was obtained from a later clock re-synchronisation. */
        TIME_RESYNCED,
        REVOKED,
        FAILURE
    }

    /** Which bulk-proof construction a site elected for its signatures. */
    public enum CommitmentScheme {
        /** 可追加承诺树: appendable Merkle-style tree, foldable per day or per site. */
        APPENDABLE_TREE,
        /** 逐份交叉签名与见证关系图: per-document cross-signature witness graph. */
        WITNESS_GRAPH
    }

    /** Leaf state inside a commitment batch; revocations/re-uploads need an extra index. */
    public enum LeafState {
        ACTIVE,
        REVOKED,
        /** Attachment re-uploaded; the leaf's existence still holds, its content state is indexed. */
        REPLACED_PENDING
    }

    /** Relation modelled by an edge in the cross-signature witness graph. */
    public enum WitnessRelation {
        GUARDIAN,
        DEVICE,
        NURSE
    }

    /** Distortion families a sampling plan is designed to distinguish. */
    public enum DistortionType {
        HASH_SUBSTITUTION,
        CERT_CHAIN_BREAK,
        TIMESTAMP_BACKFILL,
        IDENTITY_MISMATCH,
        TEMPLATE_MISALIGNMENT,
        ATTESTATION_FAILURE
    }
}
