package com.ctrial.web;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.CommitmentService;
import com.ctrial.service.ProofChainService;
import com.ctrial.service.SamplingPlannerService;
import com.ctrial.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 同意材料证明链工作台 API: template registry, signature witness ledger,
 * consistency compiler, tamper diff-er, sampling planner and the two bulk-proof
 * constructions (appendable commitment tree / witness graph).
 */
@RestController
@RequestMapping("/api/proof")
public class ProofChainController {

    private final ConsentTemplateRepo templateRepo;
    private final SignatureWitnessRepo witnessRepo;
    private final AttachmentVersionRepo attachmentRepo;
    private final ProofConclusionRepo conclusionRepo;
    private final DiffFindingRepo diffRepo;
    private final SamplingPlanRepo planRepo;
    private final SamplingResultRepo resultRepo;
    private final CommitmentBatchRepo batchRepo;
    private final WitnessGraphEdgeRepo edgeRepo;
    private final ProofChainService proofChain;
    private final SamplingPlannerService planner;
    private final CommitmentService commitment;
    private final AuditService audit;

    public ProofChainController(ConsentTemplateRepo templateRepo, SignatureWitnessRepo witnessRepo,
                                AttachmentVersionRepo attachmentRepo, ProofConclusionRepo conclusionRepo,
                                DiffFindingRepo diffRepo, SamplingPlanRepo planRepo, SamplingResultRepo resultRepo,
                                CommitmentBatchRepo batchRepo, WitnessGraphEdgeRepo edgeRepo,
                                ProofChainService proofChain, SamplingPlannerService planner,
                                CommitmentService commitment, AuditService audit) {
        this.templateRepo = templateRepo;
        this.witnessRepo = witnessRepo;
        this.attachmentRepo = attachmentRepo;
        this.conclusionRepo = conclusionRepo;
        this.diffRepo = diffRepo;
        this.planRepo = planRepo;
        this.resultRepo = resultRepo;
        this.batchRepo = batchRepo;
        this.edgeRepo = edgeRepo;
        this.proofChain = proofChain;
        this.planner = planner;
        this.commitment = commitment;
        this.audit = audit;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    // ==================== reads ====================

    @GetMapping("/templates")
    public List<ConsentTemplate> templates() {
        return templateRepo.findAllByOrderBySiteCodeAscPublishedFromAsc();
    }

    @GetMapping("/witnesses")
    public List<Map<String, Object>> witnesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            out.add(proofChain.proofRow(w));
        }
        return out;
    }

    @GetMapping("/witnesses/{signatureNo}")
    public ResponseEntity<?> witness(@PathVariable String signatureNo) {
        Optional<SignatureWitness> w = witnessRepo.findBySignatureNo(signatureNo);
        if (w.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = proofChain.proofRow(w.get());
        m.put("versions", attachmentRepo.findBySignatureNoOrderByVersionNoAsc(signatureNo));
        m.put("conclusions", conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc(signatureNo));
        m.put("diffs", diffRepo.findBySignatureNoOrderByIdAsc(signatureNo));
        m.put("graphEdges", edgeRepo.findBySignatureNoOrderByRelationAscIdAsc(signatureNo));
        return ResponseEntity.ok(m);
    }

    @GetMapping("/conclusions")
    public List<ProofConclusion> conclusions() {
        return conclusionRepo.findBySupersededFalseOrderByCompiledAtDesc();
    }

    @GetMapping("/diffs")
    public List<DiffFinding> diffs() {
        return diffRepo.findBySupersededFalseOrderByFoundAtDesc();
    }

    @GetMapping("/plans")
    public Map<String, Object> plans() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plans", planRepo.findAllByOrderByCreatedAtDesc());
        m.put("results", resultRepo.findAllByOrderByRunAtDesc());
        return m;
    }

    @GetMapping("/batches")
    public List<CommitmentBatch> batches() {
        return batchRepo.findAllByOrderByFoldedAtDesc();
    }

    @GetMapping("/batches/{code}/proof")
    public Map<String, Object> inclusion(@PathVariable String code, @RequestParam String signatureNo) {
        return commitment.inclusionProof(code, signatureNo);
    }

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        return commitment.graphView();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<SignatureWitness> all = witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc();
        Map<ProofStatus, Long> byStatus = new EnumMap<>(ProofStatus.class);
        Map<String, Long> bySite = new TreeMap<>();
        long notCompiled = 0;
        for (SignatureWitness w : all) {
            bySite.merge(w.getSiteCode(), 1L, Long::sum);
            Optional<ProofConclusion> c = conclusionRepo
                    .findFirstBySignatureNoAndSupersededFalseOrderByIdDesc(w.getSignatureNo());
            if (c.isEmpty()) {
                notCompiled++;
            } else {
                byStatus.merge(c.get().getStatus(), 1L, Long::sum);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("byStatus", byStatus);
        m.put("notCompiled", notCompiled);
        m.put("bySite", bySite);
        m.put("openDiffs", diffRepo.findBySupersededFalseOrderByFoundAtDesc().size());
        m.put("activePlans", planRepo.findByActiveTrueOrderByCreatedAtDesc().size());
        m.put("batches", batchRepo.count());
        return m;
    }

    // ==================== template registry ====================

    public record TemplateRequest(String code, String siteCode, String language, String ageBand,
                                  String protocolVersion, String title, Instant publishedFrom, Instant publishedTo,
                                  TemplateStatus status, Instant revokedAt, String fieldSummary, String note) {
    }

    @PostMapping("/templates")
    public ConsentTemplate upsertTemplate(@RequestBody TemplateRequest req, Authentication auth) {
        ConsentTemplate t = templateRepo.findByCode(req.code()).orElseGet(ConsentTemplate::new);
        t.setCode(req.code());
        t.setSiteCode(req.siteCode());
        t.setLanguage(req.language());
        t.setAgeBand(req.ageBand());
        t.setProtocolVersion(req.protocolVersion());
        t.setTitle(req.title());
        t.setPublishedFrom(req.publishedFrom());
        t.setPublishedTo(req.publishedTo());
        t.setStatus(req.status() == null ? TemplateStatus.PUBLISHED : req.status());
        t.setRevokedAt(req.revokedAt());
        t.setFieldSummary(req.fieldSummary());
        t.setFieldSummaryHash(Text.sha256(req.fieldSummary() == null ? "" : req.fieldSummary()));
        t.setNote(req.note());
        t = templateRepo.save(t);
        audit.record(actor(auth), "PROOF_TEMPLATE_UPSERTED", "ConsentTemplate", t.getCode(),
                t.getSiteCode() + " " + t.getLanguage() + " " + t.getAgeBand() + " " + t.getProtocolVersion()
                        + " interval=[" + t.getPublishedFrom() + "," + t.getPublishedTo() + ") " + t.getStatus());
        return t;
    }

    // ==================== witness ledger ====================

    public record WitnessRequest(String signatureNo, String subjectPseudonym, String localIdentityHandle,
                                 String siteCode, String templateCode, String signerCertRef,
                                 String signerCertThumbprint, String certChainThumbprints, String deviceModel,
                                 String deviceBatch, String deviceIdRef, AttestationState attestationState,
                                 String attestationDigest, Instant signedAt, Instant witnessedAt, Instant uploadedAt,
                                 Instant trustedTimeAt, boolean trustedTimeResynced, String attachmentHash,
                                 boolean nurseReviewed, String nurseReviewRef, String guardianProofRef,
                                 String guardianProofHash, boolean localWitnessPresent, Instant randomizedAt,
                                 CommitmentScheme commitmentScheme, String supplementNote, String note) {
    }

    @PostMapping("/witnesses")
    public ResponseEntity<?> registerWitness(@RequestBody WitnessRequest req, Authentication auth) {
        if (witnessRepo.findBySignatureNo(req.signatureNo()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "signature " + req.signatureNo() + " already exists; upload a new version"));
        }
        SignatureWitness w = new SignatureWitness();
        w.setSignatureNo(req.signatureNo());
        w.setSubjectPseudonym(req.subjectPseudonym());
        w.setLocalIdentityHandle(req.localIdentityHandle());
        w.setSiteCode(req.siteCode());
        w.setTemplateCode(req.templateCode());
        w.setSignerCertRef(req.signerCertRef());
        w.setSignerCertThumbprint(req.signerCertThumbprint());
        w.setCertChainThumbprints(req.certChainThumbprints());
        w.setDeviceModel(req.deviceModel());
        w.setDeviceBatch(req.deviceBatch());
        w.setDeviceIdRef(req.deviceIdRef());
        w.setAttestationState(req.attestationState());
        w.setAttestationDigest(req.attestationDigest());
        w.setSignedAt(req.signedAt());
        w.setWitnessedAt(req.witnessedAt());
        w.setUploadedAt(req.uploadedAt());
        w.setTrustedTimeAt(req.trustedTimeAt());
        w.setTrustedTimeResynced(req.trustedTimeResynced());
        w.setAttachmentHash(req.attachmentHash());
        w.setNurseReviewed(req.nurseReviewed());
        w.setNurseReviewRef(req.nurseReviewRef());
        w.setGuardianProofRef(req.guardianProofRef());
        w.setGuardianProofHash(req.guardianProofHash());
        w.setLocalWitnessPresent(req.localWitnessPresent());
        w.setRandomizedAt(req.randomizedAt());
        w.setCommitmentScheme(req.commitmentScheme() == null
                ? CommitmentScheme.APPENDABLE_TREE : req.commitmentScheme());
        w.setNote(req.note());
        w = witnessRepo.save(w);

        AttachmentVersion v1 = new AttachmentVersion();
        v1.setSignatureNo(w.getSignatureNo());
        v1.setVersionNo(1);
        v1.setHash(req.attachmentHash());
        v1.setCertChainThumbprints(req.certChainThumbprints());
        v1.setSignerCertThumbprint(req.signerCertThumbprint());
        v1.setUploadedAt(req.uploadedAt());
        v1.setUploadedByRef(req.nurseReviewed() && req.nurseReviewRef() != null ? req.nurseReviewRef() : "DEVICE");
        v1.setSupplementNote(req.supplementNote());
        attachmentRepo.save(v1);

        audit.record(actor(auth), "PROOF_WITNESS_REGISTERED", "SignatureWitness", w.getSignatureNo(),
                w.getSubjectPseudonym() + " at " + w.getSiteCode() + " signed=" + w.getSignedAt()
                        + " uploaded=" + w.getUploadedAt() + " localWitness=" + w.isLocalWitnessPresent());
        return ResponseEntity.ok(proofChain.proofRow(w));
    }

    public record AttachmentUploadRequest(String hash, String certChainThumbprints, String signerCertThumbprint,
                                          String uploadedByRef, String supplementNote, String note) {
    }

    @PostMapping("/witnesses/{signatureNo}/versions")
    public AttachmentVersion uploadVersion(@PathVariable String signatureNo,
                                           @RequestBody AttachmentUploadRequest req, Authentication auth) {
        SignatureWitness w = witnessRepo.findBySignatureNo(signatureNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown signature " + signatureNo));
        List<AttachmentVersion> existing = attachmentRepo
                .findBySignatureNoOrderByVersionNoAsc(signatureNo);
        AttachmentVersion v = new AttachmentVersion();
        v.setSignatureNo(signatureNo);
        v.setVersionNo(existing.size() + 1);
        v.setHash(req.hash());
        v.setCertChainThumbprints(req.certChainThumbprints());
        v.setSignerCertThumbprint(req.signerCertThumbprint() == null
                ? existing.get(0).getSignerCertThumbprint() : req.signerCertThumbprint());
        v.setUploadedAt(Instant.now());
        v.setUploadedByRef(req.uploadedByRef() == null ? "NURSE" : req.uploadedByRef());
        v.setSupplementNote(req.supplementNote());
        v.setNote(req.note());
        v = attachmentRepo.save(v);
        audit.record(actor(auth), "PROOF_ATTACHMENT_VERSION_UPLOADED", "AttachmentVersion",
                signatureNo + "#v" + v.getVersionNo(),
                "hash=" + v.getHash() + " chainContinuousWithV1="
                        + ProofChainService.chainContinuous(existing.get(0), v));
        return v;
    }

    // ==================== compiler / diff-er ====================

    @PostMapping("/compile")
    public List<ProofConclusion> compile(@RequestParam(required = false) String signatureNo,
                                         Authentication auth) {
        if (signatureNo != null) {
            return List.of(proofChain.compileSignature(signatureNo, actor(auth)));
        }
        return proofChain.compileAll(actor(auth));
    }

    @PostMapping("/diff")
    public List<DiffFinding> diff(@RequestParam(required = false) String signatureNo, Authentication auth) {
        if (signatureNo != null) {
            return proofChain.diffSignature(signatureNo, actor(auth));
        }
        return proofChain.diffAll(actor(auth));
    }

    // ==================== sampling planner ====================

    @PostMapping("/plans")
    public SamplingPlan createPlan(@RequestBody SamplingPlannerService.PlanRequest req, Authentication auth) {
        return planner.createPlan(req, actor(auth));
    }

    @PostMapping("/plans/results")
    public SamplingResult recordResult(@RequestBody SamplingPlannerService.SampleResultRequest req,
                                       Authentication auth) {
        return planner.recordResult(req, actor(auth));
    }

    // ==================== commitment tree / witness graph ====================

    public record FoldRequest(String siteCode, Integer bucketDay) {
    }

    @PostMapping("/batches/fold")
    public CommitmentBatch fold(@RequestBody FoldRequest req, Authentication auth) {
        return commitment.fold(req.siteCode(), req.bucketDay(), actor(auth));
    }

    public record LeafAnnotationRequest(String batchCode, String signatureNo, LeafState state, String reason) {
    }

    @PostMapping("/batches/annotate")
    public CommitmentBatch annotate(@RequestBody LeafAnnotationRequest req, Authentication auth) {
        return commitment.annotateLeaf(req.batchCode(), req.signatureNo(), req.state(), req.reason(), actor(auth));
    }

    @PostMapping("/graph/build")
    public List<WitnessGraphEdge> buildGraph(@RequestParam String signatureNo, Authentication auth) {
        return commitment.buildGraph(signatureNo, actor(auth));
    }
}
