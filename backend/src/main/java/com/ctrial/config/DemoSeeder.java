package com.ctrial.config;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import com.ctrial.service.CommitmentService;
import com.ctrial.service.ProofChainService;
import com.ctrial.service.SamplingPlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Seeds the acceptance scenario. Instants are derived from a fixed epoch so the
 * UI, the tests and this comment all speak of the same study days.
 *
 * <p>Study day 1 = 2026-03-02T09:00Z.
 * <pre>
 *  D02  B signs a global consent (global + local + safety purposes)
 *  D05  D's randomisation at CN-02 (inside the site's confirmed range)
 *  D10  A signs a China-only consent (local purpose only, region CN)
 *  D11  A's visit occurs; source accepts it D11, the wire only carries it D15
 *  D11  B's grade-3 AE occurs while global consent is in force
 *  D12  B withdraws the global purpose (signed D12 09:00, received D12 20:00)
 *  D12  D's visit occurs at CN-02, after that site's link stopped
 *  D13  C's imaging (v2.0 is approved but not yet effective)
 *  D14  protocol v2.0 becomes effective at CN-01 only, adding ADC/tumour-volume
 *  D15  C's second imaging carries the new fields
 *  D16  reference analysis day for every acceptance check
 * </pre>
 */
@Component
@Order(10)
public class DemoSeeder implements ApplicationRunner {

    public static final Instant EPOCH = Instant.parse("2026-03-02T09:00:00Z");

    private final StudyRepo studyRepo;
    private final SiteRepo siteRepo;
    private final SubjectRepo subjectRepo;
    private final ConsentRepo consentRepo;
    private final ProtocolRepo protocolRepo;
    private final EventRepo eventRepo;
    private final WatermarkRepo watermarkRepo;
    private final ApprovalRepo approvalRepo;
    private final DispositionRuleRepo ruleRepo;
    private final TieBreakRepo tieBreakRepo;
    private final ReceiptRepo receiptRepo;
    private final ConsentTemplateRepo templateRepo;
    private final SignatureWitnessRepo witnessRepo;
    private final AttachmentVersionRepo attachmentRepo;
    private final ProofChainService proofChain;
    private final SamplingPlannerService planner;
    private final CommitmentService commitment;
    private final AuditService audit;
    private final boolean seed;

    public DemoSeeder(StudyRepo studyRepo, SiteRepo siteRepo, SubjectRepo subjectRepo, ConsentRepo consentRepo,
                      ProtocolRepo protocolRepo, EventRepo eventRepo, WatermarkRepo watermarkRepo,
                      ApprovalRepo approvalRepo, DispositionRuleRepo ruleRepo, TieBreakRepo tieBreakRepo,
                      ReceiptRepo receiptRepo, ConsentTemplateRepo templateRepo, SignatureWitnessRepo witnessRepo,
                      AttachmentVersionRepo attachmentRepo, ProofChainService proofChain,
                      SamplingPlannerService planner, CommitmentService commitment, AuditService audit,
                      @Value("${app.demo.seed-on-startup:true}") boolean seed) {
        this.studyRepo = studyRepo;
        this.siteRepo = siteRepo;
        this.subjectRepo = subjectRepo;
        this.consentRepo = consentRepo;
        this.protocolRepo = protocolRepo;
        this.eventRepo = eventRepo;
        this.watermarkRepo = watermarkRepo;
        this.approvalRepo = approvalRepo;
        this.ruleRepo = ruleRepo;
        this.tieBreakRepo = tieBreakRepo;
        this.receiptRepo = receiptRepo;
        this.templateRepo = templateRepo;
        this.witnessRepo = witnessRepo;
        this.attachmentRepo = attachmentRepo;
        this.proofChain = proofChain;
        this.planner = planner;
        this.commitment = commitment;
        this.audit = audit;
        this.seed = seed;
    }

    public static Instant day(int d) {
        return EPOCH.plus(d - 1L, ChronoUnit.DAYS);
    }

    public static Instant hour(int d, int h) {
        return day(d).plus(h, ChronoUnit.HOURS);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || studyRepo.count() > 0) {
            return;
        }
        Study study = studyRepo.save(new Study("CT-2026-017", "APEX-3 multinational oncology study", EPOCH, "Helix Pharma"));

        Site cn1 = siteRepo.save(new Site("CN-01", "Shanghai Central Hospital", "CN", "CN-HUB-1", true));
        Site cn2 = siteRepo.save(new Site("CN-02", "Wuhan Union Hospital", "CN", "CN-HUB-2", true));
        Site sg = siteRepo.save(new Site("SG-01", "Singapore National Cancer Centre", "SG", "SEA-HUB-1", false));

        Subject a = subjectRepo.save(new Subject("PSN-A1", cn1, "CN", "CHN-001-0007"));
        Subject b = subjectRepo.save(new Subject("PSN-B2", sg, "SG", "SGP-004-0011"));
        Subject c = subjectRepo.save(new Subject("PSN-C3", cn1, "CN", "CHN-001-0009"));
        Subject d = subjectRepo.save(new Subject("PSN-D4", cn2, "CN", "CHN-002-0003"));

        // ---------------- protocols ----------------
        ProtocolVersion v1 = new ProtocolVersion();
        v1.setVersion("v1.2");
        v1.setTitle("APEX-3 original protocol");
        v1.setStatus(ProtocolStatus.APPROVED);
        v1.setEthicsApprovedAt(hour(1, 8));
        v1.setSponsorApprovedAt(hour(1, 9));
        v1.setEffectiveFrom(day(1));
        v1.setApplicableSites("");
        v1.setRequiredFieldGroups("DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION");
        v1.setNote("Baseline version governing every visit from day 1 at every site.");
        protocolRepo.save(v1);

        ProtocolVersion v2 = new ProtocolVersion();
        v2.setVersion("v2.0");
        v2.setTitle("APEX-3 amendment — quantitative imaging");
        v2.setStatus(ProtocolStatus.APPROVED);
        v2.setEthicsApprovedAt(hour(13, 10));
        v2.setSponsorApprovedAt(hour(13, 16));
        v2.setEffectiveFrom(day(14));
        v2.setApplicableSites("CN-01");
        v2.setRequiredFieldGroups("IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL");
        v2.setAmendsVersion("v1.2");
        v2.setNote("Adds ADC and tumour-volume imaging fields. Fully approved on day 13 but applies only from its "
                + "explicit effective date (day 14) and only at CN-01.");
        protocolRepo.save(v2);

        ProtocolVersion v3 = new ProtocolVersion();
        v3.setVersion("v3.0-draft");
        v3.setTitle("APEX-3 next amendment (sponsor approval outstanding)");
        v3.setStatus(ProtocolStatus.PARTIALLY_APPROVED);
        v3.setEthicsApprovedAt(hour(15, 11));
        v3.setSponsorApprovedAt(null);
        v3.setEffectiveFrom(day(20));
        v3.setApplicableSites("");
        v3.setRequiredFieldGroups("LABS,IMAGING_ADC");
        v3.setNote("Ethics approved, sponsor approval outstanding; anything that would fall under it stays PENDING.");
        protocolRepo.save(v3);

        // ---------------- approvals ----------------
        approval("DUA-GLOBAL-01", Purpose.GLOBAL_ANALYSIS, true, "EU,SG,DE,US",
                "CN-01,CN-02,SG-01",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 9), "Sponsor + EC (joint)",
                "Cross-border analysis envelope. Consent narrows this; it can never widen it.");

        approval("DUA-CN-LOCAL-01", Purpose.CHINA_LOCAL_ANALYSIS, false, "CN",
                "CN-01,CN-02",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 10), "China site EC",
                "China-resident source records default to this local envelope; no cross-border transfer.");

        approval("DUA-SG-LOCAL-01", Purpose.LOCAL_ANALYSIS, false, "SG",
                "SG-01",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 11), "Singapore NHG DSRB",
                "Local envelope for the SEA site.");

        // ---------------- disposition rules (registered before any withdrawal) ----------------
        DispositionRule retain = new DispositionRule();
        retain.setCode("DR-RETAIN-25Y");
        retain.setScope(DispositionScope.STUDY);
        retain.setAction(RuleAction.RETAIN_RESTRICTED);
        retain.setRetainYears(25);
        retain.setLegalBasis("ICH GCP 4.9.5 / EU CTR Art.28(3): trial records are retained 25 years after study "
                + "close for pharmacovigilance and inspection; after a withdrawal, access is restricted and the "
                + "derived rows are isolated rather than destroyed.");
        retain.setRegisteredAt(hour(2, 13));
        retain.setRegisteredBy("Sponsor + EC (joint)");
        ruleRepo.save(retain);

        // ---------------- consent ----------------
        consent(a, ConsentKind.GRANT, "CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "CN",
                hour(10, 10), hour(10, 14), "ICF-A-2026-0007",
                "Signed day 10 for China-only analysis. No global purpose is granted. Platform receipt 4h later.");
        consent(b, ConsentKind.GRANT, "GLOBAL_ANALYSIS,LOCAL_ANALYSIS,SAFETY_MONITORING",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(2, 11), hour(2, 13), "ICF-B-2026-0011",
                "Initial grant covering global, local and safety purposes.");
        consent(b, ConsentKind.WITHDRAWAL, "GLOBAL_ANALYSIS", "", "EU,SG,DE,CN",
                hour(12, 9), hour(12, 20), "ICF-B-WD-2026-0011",
                "Withdraws the global-analysis purpose only; local analysis and safety monitoring remain. "
                        + "Local signature day 12 09:00; platform receipt not until day 12 20:00.");
        consent(c, ConsentKind.GRANT, "GLOBAL_ANALYSIS,CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(1, 12), hour(1, 13), "ICF-C-2026-0009",
                "Broad grant; the protocol amendment decides which imaging fields actually apply.");
        consent(d, ConsentKind.GRANT, "GLOBAL_ANALYSIS,CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(1, 14), hour(1, 15), "ICF-D-2026-0003",
                "Broad grant at the stalled CN-02 site.");

        // ---------------- events ----------------
        // CN-01 (healthy link) — sequences 11..15
        event("VST-A1-V1", a, Domain.VISIT, hour(11, 15), hour(11, 15), "CN-HUB-1", 11, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"118/76\",\"hr\":74,\"tempC\":36.7}", hour(15, 10));
        event("LAB-A1-D11", a, Domain.LAB, hour(11, 16), hour(11, 16), "CN-HUB-1", 12, "v1.2",
                "LABS", "{\"wbc\":6.1,\"hgb\":12.8,\"plt\":241}", hour(15, 11));
        event("IMG-C3-D13", c, Domain.IMAGING, hour(13, 14), hour(13, 14), "CN-HUB-1", 13, "v1.2",
                "IMAGING", "{\"modality\":\"CT\",\"lesionMm\":22}", hour(13, 15));
        event("IMG-C3-D15", c, Domain.IMAGING, hour(15, 14), hour(15, 14), "CN-HUB-1", 14, "v2.0",
                "IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL",
                "{\"modality\":\"MRI\",\"adc\":0.94,\"tumorVolumeMl\":18.6}", hour(15, 16));
        event("AE-C3-01", c, Domain.AE, hour(12, 15), hour(12, 15), "CN-HUB-1", 15, "v1.2",
                "AE", "{\"term\":\"rash\",\"grade\":2,\"serious\":false}", hour(12, 16));

        // SG-01 (healthy link)
        event("AE-B2-01", b, Domain.AE, hour(11, 10), hour(11, 10), "SEA-HUB-1", 3, "v1.2",
                "AE", "{\"term\":\"neutropenia\",\"grade\":3,\"serious\":true}", hour(11, 12));
        event("VST-B2-V2", b, Domain.VISIT, hour(13, 11), hour(13, 11), "SEA-HUB-1", 4, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"121/79\",\"hr\":81,\"tempC\":36.9}", hour(13, 13));

        // CN-02 (link stalled at day 9) — D's D5 record is inside the confirmed range,
        // D's D12 record is above it. Sequences 10 and 11 are genuinely absent (gap).
        event("RAND-D4-01", d, Domain.RANDOMIZATION, hour(5, 11), hour(5, 11), "CN-HUB-2", 8, "v1.2",
                "RANDOMIZATION", "{\"arm\":\"B\",\"strat\":\"PD-L1<1%\"}", hour(5, 11));
        event("VST-D4-V1", d, Domain.VISIT, hour(12, 10), hour(12, 10), "CN-HUB-2", 12, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"126/82\",\"hr\":88,\"tempC\":37.1}", hour(12, 10));

        // ---------------- watermarks ----------------
        // CN-01 fully caught up (analysis folded through the last sequence)
        for (Domain dom : Domain.values()) {
            wm("CN-01", dom, 0, 0, 0, null);
        }
        advance("CN-01", Domain.VISIT, 11, 11, 11, hour(15, 10));
        advance("CN-01", Domain.LAB, 12, 12, 12, hour(15, 11));
        advance("CN-01", Domain.IMAGING, 14, 14, 14, hour(15, 16));
        advance("CN-01", Domain.AE, 15, 15, 15, hour(12, 16));

        // CN-02 link stopped after day 9: source has published through 12, transfer acked only 9
        for (Domain dom : Domain.values()) {
            wm("CN-02", dom, 0, 0, 0, null);
        }
        advance("CN-02", Domain.RANDOMIZATION, 9, 9, 9, hour(9, 23));
        advance("CN-02", Domain.VISIT, 12, 9, 9, hour(12, 10));
        for (Watermark w : watermarkRepo.findBySiteCodeOrderByDomainAsc("CN-02")) {
            w.setLinkBrokenAt(hour(9, 23));
            watermarkRepo.save(w);
        }

        // SG-01 healthy
        for (Domain dom : Domain.values()) {
            wm("SG-01", dom, 0, 0, 0, null);
        }
        advance("SG-01", Domain.AE, 3, 3, 3, hour(11, 12));
        advance("SG-01", Domain.VISIT, 4, 4, 4, hour(13, 13));

        seedProofChain(study, cn1, cn2, sg, a, b, c, d);

        audit.record("system:seed", "DEMO_SEEDED", "Study", study.getCode(),
                "Acceptance scenario A/B/C/D seeded; analysis day 16 is the reference instant. "
                        + "No tie-break rule is registered, so withdrawal/correction races must be quarantined.");
    }

    private void approval(String code, Purpose purpose, boolean crossBorder, String destinations, String sites,
                          String fields, String versions, Instant approvedAt, String by, String note) {
        DataUseApproval ap = new DataUseApproval();
        ap.setCode(code);
        ap.setPurpose(purpose);
        ap.setCrossBorder(crossBorder);
        ap.setDestinationRegions(destinations);
        ap.setSiteScope(sites);
        ap.setFieldGroups(fields);
        ap.setProtocolVersions(versions);
        ap.setObservationFrom(day(1));
        ap.setObservationTo(day(365));
        ap.setApprovedAt(approvedAt);
        ap.setApprovedBy(by);
        ap.setNote(note);
        approvalRepo.save(ap);
    }

    private void advance(String site, Domain domain, long source, long ack, long analysis, Instant at) {
        Watermark w = watermarkRepo.findBySiteCodeAndDomain(site, domain).orElseThrow();
        w.setSourceConfirmedSeq(source);
        w.setTransferAckSeq(ack);
        w.setAnalysisSeq(analysis);
        w.setSourceConfirmedAt(at);
        w.setAnalysisAt(at);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
    }

    private void consent(Subject s, ConsentKind kind, String purposes, String fields, String regions,
                         Instant effectiveFrom, Instant recordedAt, String doc, String note) {
        ConsentEntry c = new ConsentEntry();
        c.setSubject(s);
        c.setKind(kind);
        c.setPurposes(purposes);
        c.setFieldGroups(fields);
        c.setRegions(regions);
        c.setEffectiveFrom(effectiveFrom);
        c.setRecordedAt(recordedAt);
        c.setDocumentRef(doc);
        c.setNote(note);
        consentRepo.save(c);
    }

    private void event(String stableId, Subject s, Domain domain, Instant occurred, Instant received,
                       String source, long seq, String protocol, String fields, String payload, Instant ingested) {
        String normalized = com.ctrial.common.Text.csvStrings(java.util.Arrays.asList(fields.split(",")));
        String hash = com.ctrial.common.EventHash.of(stableId, 1, domain.name(), occurred, protocol,
                normalized, payload, false);
        LocalEvent e = new LocalEvent();
        e.setStableId(stableId);
        e.setRevision(1);
        e.setSubject(s);
        e.setDomain(domain);
        e.setOccurredAt(occurred);
        e.setReceivedAt(received);
        e.setIngestedAt(ingested);
        e.setSourceSystem(source);
        e.setSourceSeq(seq);
        e.setProtocolVersion(protocol);
        e.setFieldGroups(normalized);
        e.setPayloadJson(payload);
        e.setContentHash(hash);
        e = eventRepo.save(e);

        TransportReceipt receipt = new TransportReceipt();
        receipt.setSourceSystem(source);
        receipt.setDomain(domain.name());
        receipt.setSourceSeq(seq);
        receipt.setStableId(stableId);
        receipt.setRevision(1);
        receipt.setContentHash(hash);
        receipt.setAckedAt(ingested);
        receipt.setWasDuplicate(false);
        receipt.setEventId(e.getId());
        receiptRepo.save(receipt);
    }

    private void wm(String site, Domain domain, long source, long ack, long analysis, Instant at) {
        Watermark w = new Watermark();
        w.setSiteCode(site);
        w.setDomain(domain);
        w.setSourceConfirmedSeq(source);
        w.setTransferAckSeq(ack);
        w.setAnalysisSeq(analysis);
        w.setSourceConfirmedAt(at);
        w.setAnalysisAt(at);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
    }

    // ================= consent material proof chain demo =================

    private static final String TH_CN_SIGNER = com.ctrial.common.Text.sha256("CN-SIGNER-CERT-001");
    private static final String TH_SG_SIGNER = com.ctrial.common.Text.sha256("SG-SIGNER-CERT-001");
    private static final String TH_CN_ROOT = com.ctrial.common.Text.sha256("CN-ROOT-CA");
    private static final String TH_CN_ICA = com.ctrial.common.Text.sha256("CN-ICA-CA");
    private static final String TH_SG_ROOT = com.ctrial.common.Text.sha256("SG-ROOT-CA");
    private static final String TH_FOREIGN = com.ctrial.common.Text.sha256("UNKNOWN-FOREIGN-CA");
    private static final String FIELDS_ADULT = "DEMOG,SIGNATURE_DATE,CONSENT_PURPOSES,REGION,HANDSET_ID,NURSE_REVIEW";
    private static final String FIELDS_CHILD = "DEMOG,GUARDIAN_PROOF,SIGNATURE_DATE,CONSENT_PURPOSES,HANDSET_ID,NURSE_REVIEW";

    private void seedProofChain(Study study, Site cn1, Site cn2, Site sg, Subject a, Subject b, Subject c, Subject d) {
        Subject e5 = subjectRepo.save(new Subject("PSN-E5", cn1, "CN", "CHN-001-0021"));
        Subject f6 = subjectRepo.save(new Subject("PSN-F6", cn2, "CN", "CHN-002-0014"));
        Subject g7 = subjectRepo.save(new Subject("PSN-G7", cn2, "CN", "CHN-002-0015"));
        Subject i9 = subjectRepo.save(new Subject("PSN-I9", cn2, "CN", "CHN-002-0019"));

        // ---- template registry: publication intervals per centre/language/age band/revision ----
        template("TPL-CN01-ADULT-V10", "CN-01", "zh-CN", "ADULT", "v1.0", "ICF 成人版 v1.0（已撤销）",
                day(-5), day(2), TemplateStatus.REVOKED, day(2), FIELDS_ADULT,
                "Revoked after a field-summary defect; never selectable for new signatures.");
        template("TPL-CN01-ADULT-V12", "CN-01", "zh-CN", "ADULT", "v1.2", "ICF 成人版 v1.2",
                day(1), day(14), TemplateStatus.SUPERSEDED, null, FIELDS_ADULT,
                "Closed when v2.0 opened; signatures inside [D1,D14) remain governed by this interval.");
        template("TPL-CN01-ADULT-V20", "CN-01", "zh-CN", "ADULT", "v2.0", "ICF 成人版 v2.0（影像修订）",
                day(14), null, TemplateStatus.PUBLISHED, null,
                FIELDS_ADULT + ",IMAGING_ADC,IMAGING_TUMOR_VOL",
                "Adds imaging fields; only valid from day 14.");
        template("TPL-CN02-ADULT-V12", "CN-02", "zh-CN", "ADULT", "v1.2", "ICF 成人版 v1.2（武汉）",
                day(1), null, TemplateStatus.PUBLISHED, null, FIELDS_ADULT, "Site-specific Wuhan interval.");
        template("TPL-CN02-CHILD-V12", "CN-02", "zh-CN", "CHILD", "v1.2", "ICF 未成年监护人版 v1.2",
                day(1), null, TemplateStatus.PUBLISHED, null, FIELDS_CHILD,
                "Requires guardianship proof; minor signatures are counter-signed.");
        template("TPL-SG01-ADULT-V12", "SG-01", "en", "ADULT", "v1.2", "ICF adult v1.2 (Singapore)",
                day(1), null, TemplateStatus.PUBLISHED, null, FIELDS_ADULT, "English interval at the SEA site.");

        String cnChain = TH_CN_ROOT + "," + TH_CN_ICA;
        String sgChain = TH_SG_ROOT;

        // ---- 1. clean, fully witnessed signatures (VERIFIED_SIGNATURE) ----
        witness("SIG-A1-001", a, "TPL-CN01-ADULT-V12", "TAB-CN01-A2", "TAB-CN01-A2",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(10, 10), hour(10, 11), hour(10, 14), hour(10, 10), false,
                hash("SIG-A1-001-v1"), true, "NR-A1-001", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null,
                "Tablet online; nurse review completed the same morning.");
        witness("SIG-B2-001", b, "TPL-SG01-ADULT-V12", "TAB-SG01-A1", "TAB-SG01-A1",
                TH_SG_SIGNER, sgChain, AttestationState.TRUSTED,
                hour(2, 11), hour(2, 12), hour(2, 13), hour(2, 11), false,
                hash("SIG-B2-001-v1"), true, "NR-B2-001", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null, "Initial grant at Singapore.");
        witness("SIG-C3-001", c, "TPL-CN01-ADULT-V12", "TAB-CN01-A2", "TAB-CN01-A2",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(1, 12), hour(1, 12), hour(1, 13), hour(1, 12), false,
                hash("SIG-C3-001-v1"), true, "NR-C3-001", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null, "Broad grant under v1.2.");
        witness("SIG-D4-001", d, "TPL-CN02-ADULT-V12", "TAB-CN02-B7", "TAB-CN02-B7",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(1, 14), hour(1, 15), hour(1, 15), hour(1, 14), false,
                hash("SIG-D4-001-v1"), true, "NR-D4-001", null, null, true, hour(5, 11),
                CommitmentScheme.APPENDABLE_TREE, null,
                "Signed before randomisation (D5); device batch later fails attestation sampling.");

        // ---- 2. offline signature predating template publication, trusted time from later sync ----
        witness("SIG-E5-001", e5, "TPL-CN01-ADULT-V20", "TAB-CN01-A2", "TAB-CN01-A2",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(12, 8), hour(12, 9), hour(15, 12), hour(13, 9), true,
                hash("SIG-E5-001-v1"), true, "NR-E5-001", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null,
                "Tablet was offline on D12; its trusted clock was re-synchronised on D13, and the nurse"
                        + " uploaded on D15. v2.0 is only published from D14, so the signing instant is pending —"
                        + " upload time must never back-fill it.");

        // ---- 3. two pseudonyms share one guardianship proof inside overlapping windows ----
        String guardianRef = "GUARD-CN02-077";
        String guardianHash = hash("GUARDIAN-DOC-077");
        witness("SIG-F6-001", f6, "TPL-CN02-CHILD-V12", "TAB-CN02-B7", "TAB-CN02-B7",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(6, 9), hour(6, 10), hour(6, 18), hour(6, 9), false,
                hash("SIG-F6-001-v1"), true, "NR-F6-001", guardianRef, guardianHash, true, null,
                CommitmentScheme.WITNESS_GRAPH, null, "Minor; guardian counter-signature.");
        witness("SIG-G7-001", g7, "TPL-CN02-CHILD-V12", "TAB-CN02-B7", "TAB-CN02-B7",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(6, 14), hour(6, 15), hour(7, 10), hour(6, 14), false,
                hash("SIG-G7-001-v1"), true, "NR-G7-001", guardianRef, guardianHash, true, null,
                CommitmentScheme.WITNESS_GRAPH, null,
                "Different minor, same guardianship proof, overlapping window — identity-link anomaly, no merge.");

        // ---- 4a. hash changed, certificate chain continuous + nursing note: supplement pending review ----
        witness("SIG-C3-002", c, "TPL-CN01-ADULT-V20", "TAB-CN01-A2", "TAB-CN01-A2",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(15, 9), hour(15, 10), hour(15, 16), hour(15, 9), false,
                hash("SIG-C3-002-v1"), true, "NR-C3-002", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null, "Re-consent under the imaging amendment.");
        attach("SIG-C3-002", 2, hash("SIG-C3-002-v2"), TH_CN_SIGNER, cnChain, "NURSE",
                "护理补录：扫描件裁切错误，重新扫描完整页；签名与证书未变。",
                "Legitimate supplement: continuous chain, nursing explanation.");

        // ---- 4b. duplicate upload: identical hash under a new version number ----
        attach("SIG-C3-001", 2, hash("SIG-C3-001-v1"), TH_CN_SIGNER, cnChain, "NURSE",
                "Tablet re-sent the same file after reconnect.", "Duplicate upload, counted once.");

        // ---- 4c. hash changed with broken chain and no note: unverifiable replacement ----
        witness("SIG-B2-002", b, "TPL-SG01-ADULT-V12", "TAB-SG01-A1", "TAB-SG01-A1",
                TH_SG_SIGNER, sgChain, AttestationState.TRUSTED,
                hour(13, 8), hour(13, 9), hour(13, 15), hour(13, 8), false,
                hash("SIG-B2-002-v1"), true, "NR-B2-002", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null, "Re-issued paperwork after the withdrawal.");
        attach("SIG-B2-002", 2, hash("SIG-B2-002-v2-REPLACED"),
                com.ctrial.common.Text.sha256("SG-SIGNER-CERT-002-UNSEEN"), TH_FOREIGN,
                "UPLOADER", null, "Replacement arrives from an unknown CA with no explanation.");

        // ---- 5. material without any local witness: pending, never withdrawal/auto-valid ----
        witness("SIG-A1-002", a, "TPL-CN01-ADULT-V12", "TAB-CN01-A2", "TAB-CN01-A2",
                TH_CN_SIGNER, cnChain, AttestationState.TRUSTED,
                hour(12, 16), hour(12, 16), hour(13, 9), hour(12, 16), false,
                hash("SIG-A1-002-v1"), false, null, null, null, false, null,
                CommitmentScheme.APPENDABLE_TREE, null,
                "Bulk-imported PDF with no local witness record and no nurse review; held pending.");

        // ---- 6. device whose integrity statement already failed ----
        witness("SIG-I9-001", i9, "TPL-CN02-ADULT-V12", "TAB-CN02-B7", "TAB-CN02-B7",
                TH_CN_SIGNER, cnChain, AttestationState.FAILURE,
                hour(8, 10), hour(8, 11), hour(8, 17), hour(8, 10), false,
                hash("SIG-I9-001-v1"), true, "NR-I9-001", null, null, true, null,
                CommitmentScheme.APPENDABLE_TREE, null,
                "Terminal integrity statement FAILURE; same batch TAB-CN02-B7 as SIG-D4-001.");

        // ---- compile + diff every material ----
        proofChain.compileAll("system:seed");
        proofChain.diffAll("system:seed");

        // ---- appendable commitment trees (per centre) and witness graph ----
        CommitmentBatch cn01 = commitment.fold("CN-01", null, "system:seed");
        CommitmentBatch cn02 = commitment.fold("CN-02", null, "system:seed");
        CommitmentBatch sg01 = commitment.fold("SG-01", null, "system:seed");
        // revocations/re-uploads need an extra leaf-state index; the sealed root is untouched
        commitment.annotateLeaf(sg01.getCode(), "SIG-B2-001", LeafState.REVOKED,
                "global-analysis purpose withdrawn on D12; existence proof retained", "system:seed");
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            if (w.getCommitmentScheme() == CommitmentScheme.WITNESS_GRAPH
                    && w.getCommitmentBatchCode() == null) {
                commitment.buildGraph(w.getSignatureNo(), "system:seed");
            }
        }

        // ---- sampling: routine plan at CN-01 and the failed batch at CN-02 ----
        planner.createPlan(new SamplingPlannerService.PlanRequest(
                "CN-01", "TAB-CN01-A2", null, "ROUTINE_PROOF_AUDIT", 0.95), "system:seed");
        SamplingPlan batchPlan = planner.createPlan(new SamplingPlannerService.PlanRequest(
                "CN-02", "TAB-CN02-B7", "TAB-CN02-B7", "BATCH_ATTESTATION_FAILURE", 0.95), "system:seed");
        planner.recordResult(new SamplingPlannerService.SampleResultRequest(
                batchPlan.getCode(), 2, 2, List.of("ATTESTATION_FAILURE"), true,
                "Both sampled tablets report invalid integrity statements."), "system:seed");

        audit.record("system:seed", "PROOF_CHAIN_SEEDED", "SignatureWitness", "*",
                "Proof-chain scenario seeded: " + witnessRepo.count() + " witnesses, "
                        + templateRepo.count() + " template intervals, batches "
                        + batchRepoLabel(cn01, cn02, sg01)
                        + "; sampling widened batch TAB-CN02-B7 and retained prior conclusions.");
    }

    private String batchRepoLabel(CommitmentBatch... batches) {
        return java.util.Arrays.stream(batches).map(CommitmentBatch::getCode)
                .reduce((x, y) -> x + "," + y).orElse("");
    }

    private void template(String code, String site, String language, String ageBand, String protocol,
                          String title, Instant from, Instant to, TemplateStatus status, Instant revokedAt,
                          String fields, String note) {
        ConsentTemplate t = new ConsentTemplate();
        t.setCode(code);
        t.setSiteCode(site);
        t.setLanguage(language);
        t.setAgeBand(ageBand);
        t.setProtocolVersion(protocol);
        t.setTitle(title);
        t.setPublishedFrom(from);
        t.setPublishedTo(to);
        t.setStatus(status);
        t.setRevokedAt(revokedAt);
        t.setFieldSummary(fields);
        t.setFieldSummaryHash(com.ctrial.common.Text.sha256(fields));
        t.setNote(note);
        templateRepo.save(t);
    }

    @SuppressWarnings("ParameterNumber")
    private void witness(String no, Subject s, String templateCode, String model, String batch,
                         String signerThumb, String chain, AttestationState attestation,
                         Instant signedAt, Instant witnessedAt, Instant uploadedAt, Instant trustedTimeAt,
                         boolean resynced, String attachmentHash, boolean nurseReviewed, String nurseRef,
                         String guardianRef, String guardianHash, boolean localWitness, Instant randomizedAt,
                         CommitmentScheme scheme, String supplementNote, String note) {
        SignatureWitness w = new SignatureWitness();
        w.setSignatureNo(no);
        w.setSubjectPseudonym(s.getPseudonym());
        w.setLocalIdentityHandle(s.getLocalIdentity());
        w.setSiteCode(s.getSite().getCode());
        w.setTemplateCode(templateCode);
        w.setSignerCertRef("CERT-LOCAL-" + no);
        w.setSignerCertThumbprint(signerThumb);
        w.setCertChainThumbprints(chain);
        w.setDeviceModel(model);
        w.setDeviceBatch(batch);
        w.setDeviceIdRef("DEV-" + batch);
        w.setAttestationState(attestation);
        w.setAttestationDigest(com.ctrial.common.Text.sha256("ATTEST-" + no + "-" + attestation));
        w.setSignedAt(signedAt);
        w.setWitnessedAt(witnessedAt);
        w.setUploadedAt(uploadedAt);
        w.setTrustedTimeAt(trustedTimeAt);
        w.setTrustedTimeResynced(resynced);
        w.setAttachmentHash(attachmentHash);
        w.setNurseReviewed(nurseReviewed);
        w.setNurseReviewRef(nurseRef);
        w.setGuardianProofRef(guardianRef);
        w.setGuardianProofHash(guardianHash);
        w.setLocalWitnessPresent(localWitness);
        w.setRandomizedAt(randomizedAt);
        w.setCommitmentScheme(scheme);
        w.setNote(note);
        witnessRepo.save(w);

        AttachmentVersion v1 = new AttachmentVersion();
        v1.setSignatureNo(no);
        v1.setVersionNo(1);
        v1.setHash(attachmentHash);
        v1.setCertChainThumbprints(chain);
        v1.setSignerCertThumbprint(signerThumb);
        v1.setUploadedAt(uploadedAt);
        v1.setUploadedByRef(nurseReviewed ? "NURSE" : "DEVICE");
        v1.setSupplementNote(supplementNote);
        v1.setNote("first witnessed attachment version");
        attachmentRepo.save(v1);
    }

    private void attach(String no, int version, String hash, String signerThumb, String chain,
                        String uploadedBy, String supplementNote, String note) {
        AttachmentVersion v = new AttachmentVersion();
        v.setSignatureNo(no);
        v.setVersionNo(version);
        v.setHash(hash);
        v.setCertChainThumbprints(chain);
        v.setSignerCertThumbprint(signerThumb);
        v.setUploadedAt(Instant.now());
        v.setUploadedByRef(uploadedBy);
        v.setSupplementNote(supplementNote);
        v.setNote(note);
        attachmentRepo.save(v);
    }

    private static String hash(String seed) {
        return com.ctrial.common.Text.sha256(seed);
    }
}
