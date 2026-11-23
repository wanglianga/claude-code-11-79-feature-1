package com.ctrial;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.CommitmentService;
import com.ctrial.service.ProofChainService;
import com.ctrial.service.SamplingPlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-level tests for the consent material proof chain workbench. Each test
 * names the inspection rule it proves, including the four mandatory scenarios:
 * offline-vs-template timing, shared guardianship evidence, hash change with a
 * continuous chain, and a failed terminal-batch attestation.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProofChainRulesTest {

    @Autowired ProofChainService proofChain;
    @Autowired SamplingPlannerService planner;
    @Autowired CommitmentService commitment;
    @Autowired SignatureWitnessRepo witnessRepo;
    @Autowired AttachmentVersionRepo attachmentRepo;
    @Autowired ProofConclusionRepo conclusionRepo;
    @Autowired DiffFindingRepo diffRepo;
    @Autowired SamplingPlanRepo planRepo;
    @Autowired SamplingResultRepo resultRepo;
    @Autowired CommitmentBatchRepo batchRepo;

    private ProofConclusion latest(String signatureNo) {
        return conclusionRepo.findFirstBySignatureNoAndSupersededFalseOrderByIdDesc(signatureNo).orElseThrow();
    }

    private List<DiffFinding> activeDiffs(String signatureNo) {
        return diffRepo.findBySignatureNoOrderByIdAsc(signatureNo).stream()
                .filter(f -> !f.isSuperseded()).toList();
    }

    @Test
    void cleanSignature_compilesToVerifiedWithLocalEvidence() {
        ProofConclusion c = latest("SIG-A1-001");
        assertThat(c.getStatus()).isEqualTo(ProofStatus.VERIFIED_SIGNATURE);
        assertThat(c.getEvidenceRefs()).contains("witness=SIG-A1-001").contains("certChain=").contains("attestation=");
    }

    @Test
    void offlineSignatureBeforeTemplatePublication_withResyncedTrustedTime_isTimePending_andNeverBackFilled() {
        SignatureWitness w = witnessRepo.findBySignatureNo("SIG-E5-001").orElseThrow();
        ProofConclusion c = latest("SIG-E5-001");
        // the upload instant (D15) must not be used to back-fill the signing point (D12)
        assertThat(w.getSignedAt()).isBefore(w.getUploadedAt());
        assertThat(w.isTrustedTimeResynced()).isTrue();
        assertThat(c.getStatus()).isEqualTo(ProofStatus.IDENTITY_OR_TIME_PENDING);
        assertThat(c.getReasonCodes()).contains(ProofChainService.TEMPLATE_NOT_PUBLISHED_AT_SIGNING);
        assertThat(c.getReasonCodes()).contains(ProofChainService.TRUSTED_TIME_RESYNCED);
        assertThat(c.getReasonCodes()).contains(ProofChainService.UPLOAD_BACKFILL_FORBIDDEN);
        assertThat(c.getEvidenceRefs()).contains("cannot replace signedAt");
    }

    @Test
    void sharedGuardianshipProofWithOverlappingWindows_raisesIdentityLinkAnomaly_andNeverMergesMaterials() {
        List<DiffFinding> f = activeDiffs("SIG-F6-001");
        DiffFinding anomaly = f.stream().filter(d -> d.getKind() == DiffKind.IDENTITY_LINK_ANOMALY).findFirst()
                .orElseThrow(() -> new AssertionError("expected IDENTITY_LINK_ANOMALY"));
        assertThat(anomaly.getSubjectPseudonyms()).contains("PSN-F6", "PSN-G7");
        // both materials remain distinct rows; nothing was merged
        assertThat(witnessRepo.findBySignatureNo("SIG-F6-001")).isPresent();
        assertThat(witnessRepo.findBySignatureNo("SIG-G7-001")).isPresent();
        // the guardian anomaly was emitted by the compiler and retained in the conclusion history,
        // even after the later batch-uncertainty conclusion was appended
        assertThat(conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc("SIG-F6-001"))
                .anyMatch(c -> c.getReasonCodes().contains(ProofChainService.GUARDIAN_LINK_OVERLAP));
        assertThat(conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc("SIG-G7-001"))
                .anyMatch(c -> c.getReasonCodes().contains(ProofChainService.GUARDIAN_LINK_OVERLAP));
    }

    @Test
    void hashChangedWithContinuousChainAndNursingNote_keepsOriginalAndListsSupplementForReview() {
        List<AttachmentVersion> versions = attachmentRepo.findBySignatureNoOrderByVersionNoAsc("SIG-C3-002");
        assertThat(versions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(versions.get(0).getHash()).isNotEqualTo(versions.get(1).getHash());
        // original v1 material is retained untouched
        assertThat(versions.get(0).getVersionNo()).isOne();

        DiffFinding supplement = activeDiffs("SIG-C3-002").stream()
                .filter(d -> d.getKind() == DiffKind.LEGITIMATE_SUPPLEMENT).findFirst().orElseThrow();
        assertThat(supplement.isChainContinuous()).isTrue();
        assertThat(supplement.isSupplementNotePresent()).isTrue();

        ProofConclusion c = latest("SIG-C3-002");
        assertThat(c.getStatus()).isEqualTo(ProofStatus.IDENTITY_OR_TIME_PENDING);
        assertThat(c.getReasonCodes()).contains(ProofChainService.SUPPLEMENT_PENDING_REVIEW);
    }

    @Test
    void duplicateUpload_sameHash_isClassifiedDuplicateAndCountedOnce() {
        DiffFinding dup = activeDiffs("SIG-C3-001").stream()
                .filter(d -> d.getKind() == DiffKind.DUPLICATE_UPLOAD).findFirst().orElseThrow();
        assertThat(dup.isHashChanged()).isFalse();
        assertThat(attachmentRepo.findBySignatureNoOrderByVersionNoAsc("SIG-C3-001").stream()
                .map(AttachmentVersion::getHash).distinct()).hasSize(1);
    }

    @Test
    void hashChangedWithBrokenChainAndNoNote_isUnverifiableReplacement_andMaterialCorrupted() {
        DiffFinding replacement = activeDiffs("SIG-B2-002").stream()
                .filter(d -> d.getKind() == DiffKind.UNVERIFIABLE_REPLACEMENT).findFirst().orElseThrow();
        assertThat(replacement.isChainContinuous()).isFalse();
        assertThat(replacement.isSupplementNotePresent()).isFalse();
        ProofConclusion c = latest("SIG-B2-002");
        assertThat(c.getStatus()).isEqualTo(ProofStatus.MATERIAL_CORRUPTED);
        assertThat(c.getReasonCodes()).contains(ProofChainService.CERT_CHAIN_BROKEN);
        // original material is preserved rather than overwritten
        assertThat(attachmentRepo.findBySignatureNoOrderByVersionNoAsc("SIG-B2-002")).hasSize(2);
    }

    @Test
    void materialWithoutLocalWitness_isPending_neverWithdrawalOrAutoValidConsent() {
        ProofConclusion c = latest("SIG-A1-002");
        assertThat(c.getStatus()).isEqualTo(ProofStatus.IDENTITY_OR_TIME_PENDING);
        assertThat(c.getReasonCodes()).contains(ProofChainService.NO_LOCAL_WITNESS);
        // the proof workbench never flips the consent ledger's kind
        SignatureWitness w = witnessRepo.findBySignatureNo("SIG-A1-002").orElseThrow();
        assertThat(w.isLocalWitnessPresent()).isFalse();
        assertThat(c.getStatus()).isNotEqualTo(ProofStatus.VERIFIED_SIGNATURE);
    }

    @Test
    void failedDeviceBatch_widensUncertaintyScope_andRetainsPriorAuditConclusionVersions() {
        SamplingResult result = resultRepo.findAllByOrderByRunAtDesc().stream()
                .filter(r -> r.isAttestationFailure()).findFirst().orElseThrow();
        assertThat(result.getWidenedSignatureNos()).contains("SIG-D4-001", "SIG-I9-001");
        assertThat(result.getRetainedConclusionRefs()).contains("SIG-D4-001:");

        // a widened re-plan exists and covers the whole batch population
        SamplingPlan widened = planRepo.findByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(p -> "BATCH_ATTESTATION_FAILURE".equals(p.getAnomalyPattern())
                        && "TAB-CN02-B7".equals(p.getDeviceBatch()))
                .findFirst().orElseThrow();
        assertThat(widened.getUncertaintyScope()).contains("SIG-D4-001");

        // the new conclusion is explicitly uncertain, and the prior version is retained (superseded only)
        ProofConclusion current = latest("SIG-D4-001");
        assertThat(current.getReasonCodes()).contains(ProofChainService.BATCH_ATTESTATION_UNCERTAINTY);
        List<ProofConclusion> history = conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc("SIG-D4-001");
        assertThat(history).hasSizeGreaterThan(1);
        assertThat(history.get(0).isSuperseded()).isTrue();
        assertThat(history.get(0).getStatus()).isEqualTo(ProofStatus.VERIFIED_SIGNATURE);
    }

    @Test
    void commitmentTree_rootMatchesInclusionProof_andLeafAnnotationKeepsRoot() {
        CommitmentBatch batch = batchRepo.findBySiteCodeOrderByBucketDayAsc("CN-01").get(0);
        var proof = commitment.inclusionProof(batch.getCode(), "SIG-A1-001");
        assertThat(proof.get("included")).isEqualTo(true);
        assertThat(proof.get("rootMatches")).isEqualTo(true);
        String rootBefore = batch.getRootHash();
        commitment.annotateLeaf(batch.getCode(), "SIG-A1-001", LeafState.REPLACED_PENDING,
                "test annotation", "test");
        assertThat(batchRepo.findByCode(batch.getCode()).orElseThrow().getRootHash()).isEqualTo(rootBefore);
    }

    @Test
    void witnessGraph_capturesGuardianDeviceAndNurseRelations() {
        var graph = commitment.graphView();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> links = (List<java.util.Map<String, Object>>) graph.get("links");
        assertThat(links).anyMatch(l -> "SIG-F6-001".equals(l.get("source"))
                && l.get("relation") == WitnessRelation.GUARDIAN);
        assertThat(links).anyMatch(l -> l.get("relation") == WitnessRelation.DEVICE);
        assertThat(links).anyMatch(l -> l.get("relation") == WitnessRelation.NURSE);
    }

    @Test
    void recompilingAppendsANewConclusionVersionWithoutDeletingHistory() {
        int before = conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc("SIG-A1-001").size();
        proofChain.compileSignature("SIG-A1-001", "test");
        List<ProofConclusion> after = conclusionRepo.findBySignatureNoOrderByVersionNoAscIdAsc("SIG-A1-001");
        assertThat(after).hasSize(before + 1);
        assertThat(after.stream().filter(c -> !c.isSuperseded())).hasSize(1);
    }
}
