package com.ctrial.service;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 证明链核心服务: the consistency compiler (一致性编译器) and the tamper
 * diff-er (篡改差异器).
 *
 * <p>The compiler correlates each signature witness with the randomisation
 * instant, the template's valid publication interval and the subject
 * pseudonym, and emits exactly one of three proof statuses —
 * VERIFIED_SIGNATURE / MATERIAL_CORRUPTED / IDENTITY_OR_TIME_PENDING — with
 * the local evidence every status depends on. It never edits the consent
 * ledger's "agreed" flag and never auto-downgrades a material that lacks
 * local witness into a withdrawal or a valid consent.
 */
@Service
public class ProofChainService {

    // reason codes ---------------------------------------------------------
    public static final String NO_LOCAL_WITNESS = "NO_LOCAL_WITNESS";
    public static final String TEMPLATE_NOT_PUBLISHED_AT_SIGNING = "TEMPLATE_NOT_PUBLISHED_AT_SIGNING";
    public static final String TRUSTED_TIME_RESYNCED = "TRUSTED_TIME_RESYNCED";
    public static final String UPLOAD_BACKFILL_FORBIDDEN = "UPLOAD_BACKFILL_FORBIDDEN";
    public static final String SIGNATURE_AFTER_ENROLMENT = "SIGNATURE_AFTER_ENROLMENT";
    public static final String GUARDIAN_PROOF_MISSING = "GUARDIAN_PROOF_MISSING";
    public static final String GUARDIAN_LINK_OVERLAP = "GUARDIAN_LINK_OVERLAP";
    public static final String NURSE_REVIEW_PENDING = "NURSE_REVIEW_PENDING";
    public static final String HASH_MISMATCH_UNVERIFIABLE = "HASH_MISMATCH_UNVERIFIABLE";
    public static final String CERT_CHAIN_BROKEN = "CERT_CHAIN_BROKEN";
    public static final String DEVICE_ATTESTATION_FAILED = "DEVICE_ATTESTATION_FAILED";
    public static final String SUPPLEMENT_PENDING_REVIEW = "SUPPLEMENT_PENDING_REVIEW";
    public static final String BATCH_ATTESTATION_UNCERTAINTY = "BATCH_ATTESTATION_UNCERTAINTY";

    private static final Set<String> CORRUPTION_REASONS = Set.of(
            HASH_MISMATCH_UNVERIFIABLE, CERT_CHAIN_BROKEN, DEVICE_ATTESTATION_FAILED);

    private final SignatureWitnessRepo witnessRepo;
    private final ConsentTemplateRepo templateRepo;
    private final AttachmentVersionRepo attachmentRepo;
    private final ProofConclusionRepo conclusionRepo;
    private final DiffFindingRepo diffRepo;

    public ProofChainService(SignatureWitnessRepo witnessRepo, ConsentTemplateRepo templateRepo,
                             AttachmentVersionRepo attachmentRepo, ProofConclusionRepo conclusionRepo,
                             DiffFindingRepo diffRepo) {
        this.witnessRepo = witnessRepo;
        this.templateRepo = templateRepo;
        this.attachmentRepo = attachmentRepo;
        this.conclusionRepo = conclusionRepo;
        this.diffRepo = diffRepo;
    }

    // ===================== consistency compiler =====================

    @Transactional
    public List<ProofConclusion> compileAll(String actor) {
        List<ProofConclusion> out = new ArrayList<>();
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            out.add(compileOne(w, actor));
        }
        return out;
    }

    @Transactional
    public ProofConclusion compileSignature(String signatureNo, String actor) {
        SignatureWitness w = witnessRepo.findBySignatureNo(signatureNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown signature " + signatureNo));
        return compileOne(w, actor);
    }

    private ProofConclusion compileOne(SignatureWitness w, String actor) {
        List<AttachmentVersion> versions = attachmentRepo.findBySignatureNoOrderByVersionNoAsc(w.getSignatureNo());
        AttachmentVersion first = versions.isEmpty() ? null : versions.get(0);
        AttachmentVersion latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        ConsentTemplate template = templateRepo.findByCode(w.getTemplateCode()).orElse(null);

        List<String> pending = new ArrayList<>();
        List<String> corrupted = new ArrayList<>();
        List<String> evidence = new ArrayList<>();

        evidence.add("witness=" + w.getSignatureNo() + " pseudonym=" + w.getSubjectPseudonym()
                + " site=" + w.getSiteCode());
        evidence.add("clocks=signedAt=" + w.getSignedAt() + " witnessedAt=" + w.getWitnessedAt()
                + " uploadedAt=" + w.getUploadedAt());
        evidence.add("clockSource=trustedTime=" + w.getTrustedTimeAt() + " resynced=" + w.isTrustedTimeResynced()
                + " (uploadedAt may never back-fill signedAt)");
        evidence.add("template=" + w.getTemplateCode()
                + (template == null ? " NOT_FOUND"
                : " interval=[" + template.getPublishedFrom() + "," + template.getPublishedTo() + ")"
                  + " status=" + template.getStatus()));
        evidence.add("attachment=" + (latest == null ? "MISSING"
                : "v" + latest.getVersionNo() + " hash=" + latest.getHash()));
        evidence.add("certChain=" + w.getCertChainThumbprints() + " signerThumbprint=" + w.getSignerCertThumbprint());
        evidence.add("attestation=" + w.getAttestationState() + " digest=" + w.getAttestationDigest()
                + " device=" + w.getDeviceModel() + " batch=" + w.getDeviceBatch());
        evidence.add("nurseReview=" + (w.isNurseReviewed() ? w.getNurseReviewRef() : "PENDING")
                + " guardianProof=" + (w.getGuardianProofRef() == null ? "NONE" : w.getGuardianProofRef()));
        evidence.add("randomizedAt=" + w.getRandomizedAt());

        // 1) no local witness -> pending, never withdrawal, never auto-valid
        if (!w.isLocalWitnessPresent()) {
            pending.add(NO_LOCAL_WITNESS);
            evidence.add("rule=material without local witness is held " + ProofStatus.IDENTITY_OR_TIME_PENDING.name()
                    + "; it is NOT downgraded to withdrawal and NOT treated as valid consent");
        }

        // 2) template publication interval vs the local signing instant
        if (template != null && w.getSignedAt().isBefore(template.getPublishedFrom())) {
            pending.add(TEMPLATE_NOT_PUBLISHED_AT_SIGNING);
            if (w.isTrustedTimeResynced()) {
                // explicit rule: offline signature earlier than template publication and the
                // device's trusted time came from a later re-synchronisation -> time pending;
                // the upload instant must not be used to back-fill the signing point
                pending.add(TRUSTED_TIME_RESYNCED);
                pending.add(UPLOAD_BACKFILL_FORBIDDEN);
                evidence.add("rule=offline signing predates template publication; trusted time derives from a later"
                        + " clock sync -> time pending; uploadedAt " + w.getUploadedAt() + " cannot replace signedAt");
            }
        }
        if (template != null && template.getStatus() == TemplateStatus.REVOKED
                && template.getRevokedAt() != null && !w.getSignedAt().isAfter(template.getRevokedAt())) {
            pending.add(TEMPLATE_NOT_PUBLISHED_AT_SIGNING);
            evidence.add("rule=template interval revoked at " + template.getRevokedAt());
        }

        // 3) signature must not post-date randomisation/enrolment
        if (w.getRandomizedAt() != null && w.getSignedAt().isAfter(w.getRandomizedAt())) {
            pending.add(SIGNATURE_AFTER_ENROLMENT);
        }

        // 4) identity: guardianship evidence for minors, nurse review, shared guardian links
        if (w.getGuardianProofRef() != null && (w.getGuardianProofHash() == null
                || w.getGuardianProofHash().isBlank())) {
            pending.add(GUARDIAN_PROOF_MISSING);
        }
        if (isGuardianLinkOverlapping(w)) {
            pending.add(GUARDIAN_LINK_OVERLAP);
            evidence.add("rule=another pseudonym cites the same guardianship proof inside an overlapping signing"
                    + " window -> identity-link anomaly; materials are never merged");
        }
        if (w.isLocalWitnessPresent() && !w.isNurseReviewed()) {
            pending.add(NURSE_REVIEW_PENDING);
        }

        // 5) device integrity statement
        if (w.getAttestationState() == AttestationState.FAILURE
                || w.getAttestationState() == AttestationState.REVOKED) {
            corrupted.add(DEVICE_ATTESTATION_FAILED);
        }

        // 6) attachment versions: hash drift and certificate-chain continuity
        if (latest != null && first != null) {
            boolean hashChanged = !latest.getHash().equals(first.getHash());
            boolean chainContinuous = chainContinuous(first, latest);
            if (!chainContinuous) {
                corrupted.add(CERT_CHAIN_BROKEN);
            }
            if (hashChanged) {
                boolean nursingNote = latest.getSupplementNote() != null && !latest.getSupplementNote().isBlank();
                if (chainContinuous && nursingNote) {
                    // explicit rule: hash changed, chain continuous, nursing supplement note ->
                    // keep the original material, list as a supplement pending review
                    pending.add(SUPPLEMENT_PENDING_REVIEW);
                    evidence.add("rule=original attachment v1 hash " + first.getHash()
                            + " retained; newer version listed as supplement pending nurse/QA review");
                } else {
                    corrupted.add(HASH_MISMATCH_UNVERIFIABLE);
                    evidence.add("rule=hash changed without a continuous chain or supplement explanation"
                            + " -> unverifiable replacement, original material preserved");
                }
            }
        }

        ProofStatus status = !corrupted.isEmpty() ? ProofStatus.MATERIAL_CORRUPTED
                : !pending.isEmpty() ? ProofStatus.IDENTITY_OR_TIME_PENDING
                : ProofStatus.VERIFIED_SIGNATURE;

        List<String> reasons = new ArrayList<>();
        reasons.addAll(corrupted);
        reasons.addAll(pending);
        String reasonCsv = reasons.isEmpty() ? "ALL_LOCAL_EVIDENCE_MATCHES" : String.join(",", reasons);

        int versionNo = latest == null ? 1 : latest.getVersionNo();
        String digest = Text.sha256(String.join("|",
                w.getSignatureNo(), String.valueOf(versionNo), status.name(), reasonCsv,
                w.getSignedAt().toString(), w.getUploadedAt().toString(),
                latest == null ? "" : latest.getHash(), w.getCertChainThumbprints()));

        // append-only: a new conclusion version supersedes the previous one
        for (ProofConclusion old : conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc(w.getSignatureNo())) {
            if (!old.isSuperseded()) {
                old.setSuperseded(true);
                conclusionRepo.save(old);
            }
        }
        ProofConclusion c = new ProofConclusion();
        c.setSignatureNo(w.getSignatureNo());
        c.setVersionNo(versionNo);
        c.setStatus(status);
        c.setReasonCodes(reasonCsv);
        c.setEvidenceRefs(String.join("\n", evidence));
        c.setCompiledAt(Instant.now());
        c.setCompiledBy(actor);
        c.setConclusionDigest(digest);
        c.setSuperseded(false);
        return conclusionRepo.save(c);
    }

    /**
     * Two pseudonyms referencing the same guardianship proof with overlapping
     * signing windows is an identity anomaly, never a merge. The window is the
     * local [signedAt, uploadedAt] interval of each witness.
     */
    private boolean isGuardianLinkOverlapping(SignatureWitness w) {
        if (w.getGuardianProofHash() == null) {
            return false;
        }
        for (SignatureWitness other : witnessRepo.findByGuardianProofHash(w.getGuardianProofHash())) {
            if (other.getSignatureNo().equals(w.getSignatureNo())
                    || other.getSubjectPseudonym().equals(w.getSubjectPseudonym())) {
                continue;
            }
            Instant aStart = w.getSignedAt();
            Instant aEnd = w.getUploadedAt();
            Instant bStart = other.getSignedAt();
            Instant bEnd = other.getUploadedAt();
            if (!aStart.isAfter(bEnd) && !bStart.isAfter(aEnd)) {
                return true;
            }
        }
        return false;
    }

    public static boolean chainContinuous(AttachmentVersion prev, AttachmentVersion cur) {
        if (!Objects.equals(prev.getSignerCertThumbprint(), cur.getSignerCertThumbprint())) {
            return false;
        }
        List<String> before = chain(prev);
        List<String> after = chain(cur);
        // the previously observed anchor lineage must remain a prefix of the current chain
        if (before.size() > after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> chain(AttachmentVersion v) {
        List<String> out = new ArrayList<>();
        for (String t : v.getCertChainThumbprints().split(",")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    // ===================== tamper diff-er =====================

    @Transactional
    public List<DiffFinding> diffAll(String actor) {
        List<DiffFinding> out = new ArrayList<>();
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            out.addAll(diffOne(w, actor));
        }
        out.addAll(diffGuardianLinks(actor));
        return out;
    }

    @Transactional
    public List<DiffFinding> diffSignature(String signatureNo, String actor) {
        SignatureWitness w = witnessRepo.findBySignatureNo(signatureNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown signature " + signatureNo));
        List<DiffFinding> out = new ArrayList<>(diffOne(w, actor));
        out.addAll(diffGuardianLinks(actor).stream()
                .filter(f -> signatureNo.equals(f.getSignatureNo())).toList());
        return out;
    }

    private List<DiffFinding> diffOne(SignatureWitness w, String actor) {
        List<AttachmentVersion> versions = attachmentRepo.findBySignatureNoOrderByVersionNoAsc(w.getSignatureNo());
        List<DiffFinding> findings = new ArrayList<>();
        for (int i = 1; i < versions.size(); i++) {
            AttachmentVersion prev = versions.get(i - 1);
            AttachmentVersion cur = versions.get(i);
            boolean hashChanged = !cur.getHash().equals(prev.getHash());
            boolean continuous = chainContinuous(prev, cur);
            boolean note = cur.getSupplementNote() != null && !cur.getSupplementNote().isBlank();
            DiffKind kind;
            String detail;
            if (!hashChanged) {
                kind = DiffKind.DUPLICATE_UPLOAD;
                detail = "Identical attachment hash re-sent as v" + cur.getVersionNo()
                        + "; counted once, original leaf retained.";
            } else if (continuous && note) {
                kind = DiffKind.LEGITIMATE_SUPPLEMENT;
                detail = "Hash changed but the signer certificate and CA lineage are continuous and a nursing"
                        + " supplement note is present; the original material is retained and the new version is"
                        + " listed for review.";
            } else {
                kind = DiffKind.UNVERIFIABLE_REPLACEMENT;
                detail = "Hash changed without a continuous certificate chain"
                        + (note ? "" : " and without a supplement note")
                        + "; the replacement cannot be verified and the original material is preserved.";
            }
            findings.add(saveFinding(w.getSignatureNo(), kind, prev.getVersionNo() + "->" + cur.getVersionNo(),
                    hashChanged, continuous, note, prev.getHash(), cur.getHash(), null, null, detail, actor));
        }
        return findings;
    }

    private List<DiffFinding> diffGuardianLinks(String actor) {
        List<DiffFinding> findings = new ArrayList<>();
        Map<String, List<SignatureWitness>> byProof = new HashMap<>();
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            if (w.getGuardianProofHash() != null) {
                byProof.computeIfAbsent(w.getGuardianProofHash(), k -> new ArrayList<>()).add(w);
            }
        }
        for (Map.Entry<String, List<SignatureWitness>> e : byProof.entrySet()) {
            List<SignatureWitness> group = e.getValue();
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    SignatureWitness a = group.get(i);
                    SignatureWitness b = group.get(j);
                    if (a.getSubjectPseudonym().equals(b.getSubjectPseudonym())) {
                        continue;
                    }
                    if (!a.getSignedAt().isAfter(b.getUploadedAt()) && !b.getSignedAt().isAfter(a.getUploadedAt())) {
                        String pseudos = a.getSubjectPseudonym() + "," + b.getSubjectPseudonym();
                        String detail = "Pseudonyms " + pseudos + " cite guardianship proof " + a.getGuardianProofRef()
                                + " inside overlapping signing windows (" + a.getSignedAt() + ".." + a.getUploadedAt()
                                + " vs " + b.getSignedAt() + ".." + b.getUploadedAt()
                                + "); identity-link anomaly raised, materials are NOT merged.";
                        findings.add(saveFinding(a.getSignatureNo(), DiffKind.IDENTITY_LINK_ANOMALY, "n/a",
                                false, true, false, null, null, pseudos, a.getGuardianProofRef(), detail, actor));
                    }
                }
            }
        }
        return findings;
    }

    private DiffFinding saveFinding(String signatureNo, DiffKind kind, String pair, boolean hashChanged,
                                    boolean continuous, boolean note, String priorHash, String currentHash,
                                    String pseudos, String guardianRef, String detail, String actor) {
        for (DiffFinding old : diffRepo.findBySignatureNoOrderByIdAsc(signatureNo)) {
            if (!old.isSuperseded() && old.getKind() == kind) {
                old.setSuperseded(true);
                diffRepo.save(old);
            }
        }
        DiffFinding f = new DiffFinding();
        f.setSignatureNo(signatureNo);
        f.setKind(kind);
        f.setVersionPair(pair);
        f.setHashChanged(hashChanged);
        f.setChainContinuous(continuous);
        f.setSupplementNotePresent(note);
        f.setPriorHash(priorHash);
        f.setCurrentHash(currentHash);
        f.setSubjectPseudonyms(pseudos);
        f.setGuardianProofRef(guardianRef);
        f.setDetail(detail);
        f.setFoundAt(Instant.now());
        f.setSuperseded(false);
        f.setFindingDigest(Text.sha256(String.join("|", signatureNo, kind.name(), pair,
                String.valueOf(hashChanged), String.valueOf(continuous), detail)));
        return diffRepo.save(f);
    }

    /**
     * Appends an explicit uncertainty conclusion after a device-batch
     * attestation failure. The previous conclusion is retained (superseded
     * flag only, row kept) so the original audit conclusion version survives.
     */
    @Transactional
    public ProofConclusion appendBatchUncertainty(String signatureNo, String reasonCode, String detail, String actor) {
        SignatureWitness w = witnessRepo.findBySignatureNo(signatureNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown signature " + signatureNo));
        List<ProofConclusion> prior = conclusionRepo
                .findBySignatureNoOrderByVersionNoAscIdAsc(signatureNo);
        int nextVersion = prior.stream().map(ProofConclusion::getVersionNo).max(Integer::compareTo).orElse(0) + 1;
        for (ProofConclusion old : prior) {
            if (!old.isSuperseded()) {
                old.setSuperseded(true);
                conclusionRepo.save(old);
            }
        }
        String evidence = "witness=" + signatureNo + " batch=" + w.getDeviceBatch()
                + "\nrule=" + detail
                + "\npriorConclusionVersions=" + prior.stream().map(c -> "v" + c.getVersionNo() + ":" + c.getStatus())
                .reduce((a, b) -> a + "," + b).orElse("none")
                + " (retained, not overwritten)";
        ProofConclusion c = new ProofConclusion();
        c.setSignatureNo(signatureNo);
        c.setVersionNo(nextVersion);
        c.setStatus(ProofStatus.IDENTITY_OR_TIME_PENDING);
        c.setReasonCodes(reasonCode);
        c.setEvidenceRefs(evidence);
        c.setCompiledAt(Instant.now());
        c.setCompiledBy(actor);
        c.setConclusionDigest(Text.sha256(String.join("|", signatureNo, String.valueOf(nextVersion),
                c.getStatus().name(), reasonCode, detail)));
        c.setSuperseded(false);
        return conclusionRepo.save(c);
    }

    // ===================== reads =====================

    @Transactional(readOnly = true)
    public Map<String, Object> proofRow(SignatureWitness w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signatureNo", w.getSignatureNo());
        m.put("subjectPseudonym", w.getSubjectPseudonym());
        m.put("site", w.getSiteCode());
        m.put("templateCode", w.getTemplateCode());
        m.put("signedAt", w.getSignedAt());
        m.put("witnessedAt", w.getWitnessedAt());
        m.put("uploadedAt", w.getUploadedAt());
        m.put("trustedTimeResynced", w.isTrustedTimeResynced());
        m.put("deviceModel", w.getDeviceModel());
        m.put("deviceBatch", w.getDeviceBatch());
        m.put("attestationState", w.getAttestationState());
        m.put("localWitnessPresent", w.isLocalWitnessPresent());
        m.put("nurseReviewed", w.isNurseReviewed());
        m.put("guardianProofRef", w.getGuardianProofRef());
        m.put("randomizedAt", w.getRandomizedAt());
        m.put("commitmentScheme", w.getCommitmentScheme());
        m.put("commitmentBatchCode", w.getCommitmentBatchCode());
        m.put("attachmentHash", w.getAttachmentHash());
        m.put("note", w.getNote());
        conclusionRepo.findFirstBySignatureNoAndSupersededFalseOrderByIdDesc(w.getSignatureNo())
                .ifPresent(c -> {
                    m.put("status", c.getStatus());
                    m.put("reasonCodes", c.getReasonCodes());
                    m.put("evidenceRefs", c.getEvidenceRefs());
                    m.put("conclusionId", c.getId());
                    m.put("conclusionDigest", c.getConclusionDigest());
                    m.put("compiledAt", c.getCompiledAt());
                });
        if (!m.containsKey("status")) {
            m.put("status", "NOT_COMPILED");
        }
        return m;
    }

    public static Set<String> corruptionReasons() {
        return CORRUPTION_REASONS;
    }
}
