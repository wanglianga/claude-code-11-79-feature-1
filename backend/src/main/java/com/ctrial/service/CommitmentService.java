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
 * 承诺树与见证关系图.
 *
 * <p>The appendable commitment tree folds signing hashes per day or per centre
 * into a Merkle-style root: the root plus the ordered leaf index proves that a
 * given material already existed at fold time. Revocations and attachment
 * re-uploads never rewrite a leaf — the original hash stays in the index and
 * the new state is explained by an extra leaf-state index.
 *
 * <p>The alternative construction ({@link CommitmentScheme#WITNESS_GRAPH})
 * stores per-document cross-signature edges between the signature and its
 * guardian proof, terminal and nurse, which traces guardianship/device/nurse
 * breaks precisely at higher storage and batch-audit cost. Sites elect one
 * scheme per signature; both are served here.
 */
@Service
public class CommitmentService {

    private final CommitmentBatchRepo batchRepo;
    private final SignatureWitnessRepo witnessRepo;
    private final AttachmentVersionRepo attachmentRepo;
    private final WitnessGraphEdgeRepo edgeRepo;
    private final StudyRepo studyRepo;
    private final StudyClock clock;
    private final AuditService audit;

    public CommitmentService(CommitmentBatchRepo batchRepo, SignatureWitnessRepo witnessRepo,
                             AttachmentVersionRepo attachmentRepo, WitnessGraphEdgeRepo edgeRepo,
                             StudyRepo studyRepo, StudyClock clock, AuditService audit) {
        this.batchRepo = batchRepo;
        this.witnessRepo = witnessRepo;
        this.attachmentRepo = attachmentRepo;
        this.edgeRepo = edgeRepo;
        this.studyRepo = studyRepo;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Fold every not-yet-folded tree-scheme signature of a site (and optional
     * study day) into one appendable batch.
     */
    @Transactional
    public CommitmentBatch fold(String siteCode, Integer bucketDay, String actor) {
        List<SignatureWitness> pending = witnessRepo.findBySiteCodeOrderBySignedAtAsc(siteCode).stream()
                .filter(w -> w.getCommitmentScheme() == CommitmentScheme.APPENDABLE_TREE)
                .filter(w -> w.getCommitmentBatchCode() == null)
                .toList();
        if (pending.isEmpty()) {
            throw new IllegalStateException("No unfolded tree-scheme signatures at site " + siteCode);
        }
        Study study = studyRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No study registered"));
        int day = bucketDay != null ? bucketDay
                : clock.dayOf(study, pending.get(0).getSignedAt());

        List<String> leafLines = new ArrayList<>();
        List<String> stateLines = new ArrayList<>();
        List<String> leafHashes = new ArrayList<>();
        for (SignatureWitness w : pending) {
            LeafState leafState = initialLeafState(w);
            leafLines.add(w.getSignatureNo() + ":" + w.getAttachmentHash() + ":" + leafState);
            leafHashes.add(Text.sha256(w.getSignatureNo() + "|" + w.getAttachmentHash()));
            if (leafState != LeafState.ACTIVE) {
                stateLines.add(w.getSignatureNo() + "=" + leafState + " at fold; original hash retained");
            }
        }
        String root = merkleRoot(leafHashes);

        CommitmentBatch batch = new CommitmentBatch();
        batch.setCode("CBT-" + siteCode + "-D" + day + "-" + (batchRepo.count() + 1));
        batch.setSiteCode(siteCode);
        batch.setBucketDay(day);
        batch.setFoldedAt(Instant.now());
        batch.setLeafCount(pending.size());
        batch.setRootHash(root);
        batch.setLeafIndex(String.join("\n", leafLines));
        batch.setLeafStateIndex(stateLines.isEmpty() ? null : String.join("\n", stateLines));
        batch.setSealed(true);
        batch = batchRepo.save(batch);

        for (SignatureWitness w : pending) {
            w.setCommitmentBatchCode(batch.getCode());
            witnessRepo.save(w);
        }
        audit.record(actor, "COMMITMENT_FOLDED", "CommitmentBatch", batch.getCode(),
                "site=" + siteCode + " day=" + day + " leaves=" + batch.getLeafCount() + " root=" + root);
        return batch;
    }

    private LeafState initialLeafState(SignatureWitness w) {
        List<AttachmentVersion> versions = attachmentRepo.findBySignatureNoOrderByVersionNoAsc(w.getSignatureNo());
        if (versions.size() > 1 && versions.stream().anyMatch(v ->
                !v.getHash().equals(versions.get(0).getHash()))) {
            return LeafState.REPLACED_PENDING;
        }
        return LeafState.ACTIVE;
    }

    /**
     * Append a leaf-state explanation (revocation / re-upload) without changing
     * the sealed root or the original leaf hash — the extra index the spec
     * requires for revoked/re-uploaded leaves.
     */
    @Transactional
    public CommitmentBatch annotateLeaf(String batchCode, String signatureNo, LeafState state, String reason,
                                        String actor) {
        CommitmentBatch batch = batchRepo.findByCode(batchCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown batch " + batchCode));
        String line = signatureNo + "=" + state + " (" + reason + ") at " + Instant.now();
        batch.setLeafStateIndex(batch.getLeafStateIndex() == null ? line
                : batch.getLeafStateIndex() + "\n" + line);
        batch = batchRepo.save(batch);
        audit.record(actor, "COMMITMENT_LEAF_ANNOTATED", "CommitmentBatch", batchCode,
                signatureNo + " -> " + state + ": " + reason + " (root unchanged: " + batch.getRootHash() + ")");
        return batch;
    }

    /** Inclusion proof: the ordered leaf position and the sibling hashes to the root. */
    @Transactional(readOnly = true)
    public Map<String, Object> inclusionProof(String batchCode, String signatureNo) {
        CommitmentBatch batch = batchRepo.findByCode(batchCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown batch " + batchCode));
        List<String> leaves = new ArrayList<>();
        for (String line : batch.getLeafIndex().split("\n")) {
            String[] parts = line.split(":");
            leaves.add(Text.sha256(parts[0] + "|" + parts[1]));
        }
        int index = -1;
        String leafHash = null;
        String[] rawLines = batch.getLeafIndex().split("\n");
        for (int i = 0; i < rawLines.length; i++) {
            if (rawLines[i].startsWith(signatureNo + ":")) {
                index = i;
                leafHash = leaves.get(i);
                break;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (index < 0) {
            m.put("included", false);
            return m;
        }
        List<String> siblings = new ArrayList<>();
        List<String> level = leaves;
        int idx = index;
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                String left = level.get(i);
                String right = i + 1 < level.size() ? level.get(i + 1) : left; // odd node duplicates
                if (i == idx || i + 1 == idx) {
                    siblings.add(i == idx ? "R:" + right : "L:" + left);
                }
                next.add(Text.sha256(left + "|" + right));
            }
            level = next;
            idx /= 2;
        }
        m.put("included", true);
        m.put("batch", batchCode);
        m.put("leafIndex", index);
        m.put("leafHash", leafHash);
        m.put("siblings", siblings);
        m.put("rootHash", batch.getRootHash());
        m.put("recomputedRoot", level.get(0));
        m.put("rootMatches", level.get(0).equals(batch.getRootHash()));
        m.put("leafStateIndex", batch.getLeafStateIndex());
        return m;
    }

    static String merkleRoot(List<String> leafHashes) {
        List<String> level = new ArrayList<>(leafHashes);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                String left = level.get(i);
                String right = i + 1 < level.size() ? level.get(i + 1) : left;
                next.add(Text.sha256(left + "|" + right));
            }
            level = next;
        }
        return level.get(0);
    }

    // ===================== witness graph =====================

    /** Build (idempotently) the guardian/device/nurse edges for one signature. */
    @Transactional
    public List<WitnessGraphEdge> buildGraph(String signatureNo, String actor) {
        SignatureWitness w = witnessRepo.findBySignatureNo(signatureNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown signature " + signatureNo));
        if (!edgeRepo.findBySignatureNoOrderByRelationAscIdAsc(signatureNo).isEmpty()) {
            return edgeRepo.findBySignatureNoOrderByRelationAscIdAsc(signatureNo);
        }
        List<WitnessGraphEdge> edges = new ArrayList<>();
        if (w.getGuardianProofRef() != null) {
            edges.add(edge(w, WitnessRelation.GUARDIAN, w.getGuardianProofRef(), w.getGuardianProofHash(),
                    w.getGuardianProofHash() == null, "guardianship proof digest missing"));
        }
        edges.add(edge(w, WitnessRelation.DEVICE, w.getDeviceIdRef(), w.getAttestationDigest(),
                w.getAttestationState() == AttestationState.FAILURE
                        || w.getAttestationState() == AttestationState.REVOKED,
                "attestation " + w.getAttestationState()));
        edges.add(edge(w, WitnessRelation.NURSE,
                w.isNurseReviewed() ? w.getNurseReviewRef() : "NURSE-REVIEW-PENDING",
                null, !w.isNurseReviewed(), "nurse review not completed"));
        Iterable<WitnessGraphEdge> saved = edgeRepo.saveAll(edges);
        audit.record(actor, "WITNESS_GRAPH_BUILT", "SignatureWitness", signatureNo,
                edges.size() + " cross-signature edges, broken=" + edges.stream().filter(WitnessGraphEdge::isBroken).count());
        List<WitnessGraphEdge> out = new ArrayList<>();
        saved.forEach(out::add);
        return out;
    }

    private WitnessGraphEdge edge(SignatureWitness w, WitnessRelation relation, String target, String digest,
                                  boolean broken, String reason) {
        WitnessGraphEdge e = new WitnessGraphEdge();
        e.setSignatureNo(w.getSignatureNo());
        e.setRelation(relation);
        e.setTargetRef(target);
        e.setTargetDigest(digest);
        e.setObservedAt(w.getWitnessedAt());
        e.setBroken(broken);
        e.setBreakReason(broken ? reason : null);
        return e;
    }

    /** Compact adjacency view for the UI, marking where the relation chain breaks. */
    @Transactional(readOnly = true)
    public Map<String, Object> graphView() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        for (SignatureWitness w : witnessRepo.findAllByOrderBySignedAtAscSignatureNoAsc()) {
            addNode(nodeIds, nodes, w.getSignatureNo(), "SIGNATURE", w.getSubjectPseudonym());
        }
        for (WitnessGraphEdge e : edgeRepo.findAllByOrderByObservedAtAsc()) {
            addNode(nodeIds, nodes, e.getTargetRef(), e.getRelation().name(), e.getTargetRef());
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("source", e.getSignatureNo());
            link.put("target", e.getTargetRef());
            link.put("relation", e.getRelation());
            link.put("broken", e.isBroken());
            link.put("reason", e.getBreakReason());
            links.add(link);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", nodes);
        m.put("links", links);
        m.put("brokenEdges", links.stream().filter(l -> (boolean) l.get("broken")).count());
        return m;
    }

    private void addNode(Set<String> ids, List<Map<String, Object>> nodes, String id, String type, String label) {
        if (ids.add(id)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("type", type);
            n.put("label", label);
            nodes.add(n);
        }
    }
}
