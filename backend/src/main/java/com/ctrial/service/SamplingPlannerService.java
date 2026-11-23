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
 * 抽检计划器. Computes the minimum review sample per centre / terminal model /
 * anomaly pattern with an attribute-sampling formula, and names the distortion
 * family the sample is expected to distinguish. When a sampled terminal batch
 * fails its integrity statement, the planner widens the proof-uncertainty
 * scope to every material produced by that batch while retaining the prior
 * audit conclusion versions (they are append-only and never overwritten).
 */
@Service
public class SamplingPlannerService {

    private final SamplingPlanRepo planRepo;
    private final SamplingResultRepo resultRepo;
    private final SignatureWitnessRepo witnessRepo;
    private final ProofConclusionRepo conclusionRepo;
    private final ProofChainService proofChain;
    private final AuditService audit;

    public SamplingPlannerService(SamplingPlanRepo planRepo, SamplingResultRepo resultRepo,
                                  SignatureWitnessRepo witnessRepo, ProofConclusionRepo conclusionRepo,
                                  ProofChainService proofChain, AuditService audit) {
        this.planRepo = planRepo;
        this.resultRepo = resultRepo;
        this.witnessRepo = witnessRepo;
        this.conclusionRepo = conclusionRepo;
        this.proofChain = proofChain;
        this.audit = audit;
    }

    public record PlanRequest(String siteCode, String deviceModel, String deviceBatch,
                              String anomalyPattern, Double confidenceLevel) {
    }

    @Transactional
    public SamplingPlan createPlan(PlanRequest req, String actor) {
        String site = req.siteCode() == null || req.siteCode().isBlank() ? "*" : req.siteCode();
        String model = req.deviceModel() == null || req.deviceModel().isBlank() ? "*" : req.deviceModel();
        String pattern = req.anomalyPattern() == null || req.anomalyPattern().isBlank()
                ? "ROUTINE_PROOF_AUDIT" : req.anomalyPattern();
        double confidence = req.confidenceLevel() == null ? 0.95 : req.confidenceLevel();

        List<SignatureWitness> population = populationOf(site, model, req.deviceBatch());
        int n = minimumSample(population.size(), confidence, expectedDeviationRate(pattern));
        List<DistortionType> distortions = expectedDistortions(pattern);

        SamplingPlan plan = new SamplingPlan();
        plan.setCode("SP-" + site + "-" + (planRepo.count() + 1) + "-" + Math.abs(pattern.hashCode() % 1000));
        plan.setSiteCode(site);
        plan.setDeviceModel(model);
        plan.setDeviceBatch(req.deviceBatch());
        plan.setAnomalyPattern(pattern);
        plan.setPopulationSize(population.size());
        plan.setSampleSize(n);
        plan.setExpectedDistortions(Text.csv(distortions));
        plan.setConfidenceLevel(confidence);
        plan.setCreatedAt(Instant.now());
        plan.setCreatedBy(actor);
        plan.setActive(true);
        plan.setPlanDigest(Text.sha256(String.join("|", site, model, String.valueOf(population.size()),
                String.valueOf(n), pattern, String.valueOf(confidence))));
        plan = planRepo.save(plan);
        audit.record(actor, "SAMPLING_PLAN_ISSUED", "SamplingPlan", plan.getCode(),
                "site=" + site + " model=" + model + " population=" + population.size() + " sample=" + n
                        + " pattern=" + pattern + " distortions=" + plan.getExpectedDistortions());
        return plan;
    }

    /**
     * Attribute sampling: smallest n whose detection probability meets the
     * confidence level at the pattern's expected deviation rate, with a floor
     * scaled to site size so tiny centres still review something meaningful.
     */
    int minimumSample(int population, double confidence, double deviationRate) {
        if (population == 0) {
            return 0;
        }
        int floor = Math.max(2, (int) Math.ceil(population * 0.10));
        if (deviationRate <= 0) {
            return Math.min(floor, population);
        }
        long n = (long) Math.ceil(Math.log(1 - confidence) / Math.log(1 - deviationRate));
        int sample = (int) Math.max(floor, Math.min(n, population));
        return sample;
    }

    private double expectedDeviationRate(String pattern) {
        return switch (pattern) {
            case "HASH_SUBSTITUTION", "BATCH_ATTESTATION_FAILURE" -> 0.08;
            case "CERT_CHAIN_BREAK", "IDENTITY_LINK_ANOMALY" -> 0.05;
            case "TIMESTAMP_BACKFILL", "TEMPLATE_MISALIGNMENT" -> 0.03;
            default -> 0.05; // routine audit
        };
    }

    private List<DistortionType> expectedDistortions(String pattern) {
        return switch (pattern) {
            case "HASH_SUBSTITUTION" -> List.of(DistortionType.HASH_SUBSTITUTION,
                    DistortionType.CERT_CHAIN_BREAK);
            case "CERT_CHAIN_BREAK" -> List.of(DistortionType.CERT_CHAIN_BREAK,
                    DistortionType.HASH_SUBSTITUTION);
            case "IDENTITY_LINK_ANOMALY" -> List.of(DistortionType.IDENTITY_MISMATCH,
                    DistortionType.TEMPLATE_MISALIGNMENT);
            case "TIMESTAMP_BACKFILL" -> List.of(DistortionType.TIMESTAMP_BACKFILL,
                    DistortionType.TEMPLATE_MISALIGNMENT);
            case "TEMPLATE_MISALIGNMENT" -> List.of(DistortionType.TEMPLATE_MISALIGNMENT,
                    DistortionType.TIMESTAMP_BACKFILL);
            case "BATCH_ATTESTATION_FAILURE" -> List.of(DistortionType.ATTESTATION_FAILURE,
                    DistortionType.HASH_SUBSTITUTION, DistortionType.CERT_CHAIN_BREAK,
                    DistortionType.TIMESTAMP_BACKFILL);
            default -> List.of(DistortionType.HASH_SUBSTITUTION, DistortionType.CERT_CHAIN_BREAK,
                    DistortionType.TIMESTAMP_BACKFILL, DistortionType.IDENTITY_MISMATCH,
                    DistortionType.TEMPLATE_MISALIGNMENT, DistortionType.ATTESTATION_FAILURE);
        };
    }

    private List<SignatureWitness> populationOf(String site, String model, String batch) {
        List<SignatureWitness> all = witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc();
        return all.stream()
                .filter(w -> "*".equals(site) || w.getSiteCode().equals(site))
                .filter(w -> "*".equals(model) || w.getDeviceModel().equals(model))
                .filter(w -> batch == null || batch.isBlank() || w.getDeviceBatch().equals(batch))
                .toList();
    }

    public record SampleResultRequest(String planCode, int reviewedCount, int failedCount,
                                      List<String> distortionsObserved, boolean attestationFailure,
                                      String note) {
    }

    /**
     * Record the execution of a plan. If the sample shows a terminal-batch
     * integrity-statement failure, widen the uncertainty scope and preserve
     * the previous conclusion versions.
     */
    @Transactional
    public SamplingResult recordResult(SampleResultRequest req, String actor) {
        SamplingPlan plan = planRepo.findByCode(req.planCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan " + req.planCode()));

        SamplingResult result = new SamplingResult();
        result.setCode("SR-" + (resultRepo.count() + 1) + "-" + plan.getSiteCode());
        result.setPlanCode(plan.getCode());
        result.setSiteCode(plan.getSiteCode());
        result.setDeviceBatch(plan.getDeviceBatch());
        result.setReviewedCount(req.reviewedCount());
        result.setFailedCount(req.failedCount());
        result.setDistortionsObserved(Text.csvStrings(req.distortionsObserved() == null
                ? List.of() : req.distortionsObserved()));
        result.setAttestationFailure(req.attestationFailure());
        result.setRunAt(Instant.now());
        result.setRunBy(actor);

        if (req.attestationFailure()) {
            widenForBatchFailure(plan, result, actor);
            result.setSummary("Batch " + plan.getDeviceBatch() + " integrity statements failed in "
                    + req.failedCount() + "/" + req.reviewedCount() + " reviews; uncertainty widened to every"
                    + " material of the batch and prior audit conclusions retained. "
                    + (req.note() == null ? "" : req.note()));
        } else {
            result.setSummary(req.failedCount() == 0
                    ? "Sample of " + req.reviewedCount() + " passed; no distortion distinguished."
                    : req.failedCount() + " distortion(s) in " + req.reviewedCount() + " reviews."
                      + (req.note() == null ? "" : " " + req.note()));
        }
        result = resultRepo.save(result);
        audit.record(actor, "SAMPLING_RESULT_RECORDED", "SamplingResult", result.getCode(), result.getSummary());
        return result;
    }

    private void widenForBatchFailure(SamplingPlan plan, SamplingResult result, String actor) {
        String batch = plan.getDeviceBatch();
        List<SignatureWitness> batchWitnesses = batch == null ? List.of() : witnessRepo.findByDeviceBatch(batch);
        List<String> widened = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        for (SignatureWitness w : batchWitnesses) {
            widened.add(w.getSignatureNo());
            // retain the current conclusion version BEFORE appending the widened one
            conclusionRepo.findFirstBySignatureNoAndSupersededFalseOrderByIdDesc(w.getSignatureNo())
                    .ifPresent(prior -> retained.add(w.getSignatureNo() + ":" + prior.getId()
                            + "(" + prior.getStatus() + ")"));
        }
        result.setWidenedSignatureNos(String.join(",", widened));
        result.setRetainedConclusionRefs(String.join(";", retained));

        // supersede the plan and re-issue with the widened uncertainty scope
        plan.setActive(false);
        SamplingPlan widenedPlan = new SamplingPlan();
        widenedPlan.setCode("SP-WIDEN-" + batch + "-" + (planRepo.count() + 1));
        widenedPlan.setSiteCode(plan.getSiteCode());
        widenedPlan.setDeviceModel(plan.getDeviceModel());
        widenedPlan.setDeviceBatch(batch);
        widenedPlan.setAnomalyPattern("BATCH_ATTESTATION_FAILURE");
        widenedPlan.setPopulationSize(batchWitnesses.size());
        widenedPlan.setSampleSize(batchWitnesses.size());
        widenedPlan.setExpectedDistortions(Text.csv(expectedDistortions("BATCH_ATTESTATION_FAILURE")));
        widenedPlan.setConfidenceLevel(plan.getConfidenceLevel());
        widenedPlan.setUncertaintyScope(String.join(",", widened));
        widenedPlan.setCreatedAt(Instant.now());
        widenedPlan.setCreatedBy(actor);
        widenedPlan.setActive(true);
        widenedPlan.setPlanDigest(Text.sha256(String.join("|", widenedPlan.getCode(),
                String.join(",", widened), String.valueOf(Instant.now().getEpochSecond()))));
        plan.setSupersededBy(widenedPlan.getCode());
        planRepo.save(plan);
        planRepo.save(widenedPlan);

        // append a new, explicitly-uncertain conclusion per material; prior rows stay in the table
        for (SignatureWitness w : batchWitnesses) {
            proofChain.appendBatchUncertainty(w.getSignatureNo(),
                    ProofChainService.BATCH_ATTESTATION_UNCERTAINTY,
                    "device batch " + batch + " attestation failed in sampling " + plan.getCode()
                            + "; prior conclusion retained", actor);
        }
    }
}
