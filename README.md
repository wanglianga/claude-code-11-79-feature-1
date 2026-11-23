# APEX-3 · Dual-Domain Sync & Cross-Border Egress Control

A platform for a multinational clinical trial that keeps China source records resident locally while letting a
global team use **de-identified derived data** only inside an approved purpose, field, site, observation-window and
protocol-version envelope. It implements the consent ledger, protocol catalogue, dual-domain eligibility
evaluator, watermark/back-fill coordination, snapshot delivery compiler, withdrawal propagator and post-outage
recovery verifier, together with the acceptance scenario for subjects A/B/C/D at analysis day 16.

It also ships an independent **consent material proof-chain workbench (同意材料证明链工作台)**. Where the consent
ledger's "agreed" flag only feeds eligibility, the workbench verifies the *provenance integrity* of each consent
fact: a template registry with publication intervals per centre/language/age band/protocol revision, a signature
witness ledger holding local-domain certificate thumbprints, terminal integrity statements, the three separate
clocks (signed/witnessed/uploaded), attachment hashes, nurse review and guardianship proofs; a consistency
compiler emitting the three proof statuses with their local evidence; a tamper diff-er over attachment versions;
a sampling planner; and both bulk-proof constructions (appendable commitment tree and per-document witness
graph). Identity plaintext never leaves the residency domain, and a material without local witness is never
auto-downgraded to a withdrawal or to a valid consent.

Stack: **Vue 3 (Vite)** · **Spring Boot 3.5 / JDK** · **PostgreSQL**. Single page app is bundled into the Spring
Boot jar and served together with the REST API.

---

## 1. Quick start

### Option A — Docker Compose (one command)

```bash
cd /workspace/79
docker compose up --build
# open http://localhost:8080   (Log in as admin / demo123)
```

Compose builds the frontend, bundles it into the Spring Boot jar, starts PostgreSQL and the app, and seeds the
demo scenario on first boot. Health check: `curl http://localhost:8080/actuator/health`.

> The provided container in this workspace has **no Docker daemon**, so Compose was not executed here. The same
> image build was validated by running the packaged jar directly (Option B) plus the integration/UI test suites.

### Option B — Run locally (used for the verification below)

Prerequisites already present in this environment: `/opt/jdk-25`, `/opt/maven`, `/opt/node-22`, PostgreSQL on
`127.0.0.1:5432` (user/db `ccdev`), Redis not required.

```bash
# 1. database (already running in the workspace image)
psql "host=127.0.0.1 user=ccdev password=ccdev dbname=postgres" -c "CREATE DATABASE ctrial;"   # once

# 2. build the SPA, bundle it, and build the backend jar
cd frontend && npm install && npm run build && cd ..
cd backend  && mvn -DskipTests package

# 3. run
java -jar backend/target/ctrial-sync-1.0.0.jar
# open http://localhost:8080  — schema and demo data seed automatically on first start
```

Frontend developer mode (hot reload, proxies `/api` to :8080):

```bash
cd frontend && npm run dev      # http://localhost:5173
```

The backend reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `DEMO_SEED` from the environment
(defaults in `backend/src/main/resources/application.yml`).

---

## 2. Test accounts (HTTP Basic)

All demo passwords are `demo123`. Roles are enforced server-side.

| Username | Role | Can do |
|----------|------|--------|
| `admin`    | ADMIN | everything |
| `global`   | GLOBAL_ANALYST (+AUDITOR) | compile cross-border deliveries, read snapshots |
| `china`    | CN_ANALYST | drive watermark / transfer / link operations |
| `auditor`  | AUDITOR | read-only audit, recovery, quarantine |
| `sponsor`  | SPONSOR | record consent & protocol versions |

Role checks that matter and were exercised: a China analyst gets **403** on `POST /api/deliveries/**`; an auditor
gets **403** on `POST /api/consents`.

---

## 3. How the domain model maps to the requirements

| Requirement | Where it lives |
|-------------|----------------|
| Consent ledger: subject, purpose, field group, region, validity interval | `ConsentEntry` — two clocks: `effectiveFrom`/`effectiveTo` (local signature) and `recordedAt` (receipt) |
| Consent validity uses the **local** signed/withdrawn instant, not receipt time | `EligibilityEngine.resolveConsent`, `EligibilityService.loadWorld` (filters on `effectiveFrom ≤ analysis`) |
| Protocol catalogue: version, approvals, effective time, applicable sites | `ProtocolVersion.fullyApproved/approvedAt/activeAt/appliesToSite` |
| Amendment applies only from explicit effective date, only after ethics **and** sponsor approval | `EligibilityEngine.resolveProtocol` |
| Local event ledger: stable id, event time, receipt time, revision chain, source, void | `LocalEvent` (unique on `(sourceSystem,domain,sourceSeq)` and `(stableId,revision)`; no direct identifiers) |
| No raw identity leaves China | `Subject.localIdentity` never projected; `DeliveryRecord` has only pseudonym + projected fields |
| Eligibility per record: 本地可用 / 全球可用 / 待定 + reasons | `Eligibility.LOCAL_AVAILABLE / GLOBAL_AVAILABLE / PENDING / BLOCKED` + `reasonCodes` |
| Intersection (not union) of consent, protocol, approval, purpose scope | `EligibilityEngine.evaluate` — sequential gates, any failure blocks, any undeterminable pends |
| Watermark coordinator: source-confirmed / transfer-ack / analysis, per domain | `Watermark` (three counters), `StateService`, `coverage` view |
| Gaps are UNCONFIRMED, never zero | `WATERMARK_UNCONFIRMED` reason + analysis cap; UI gap band |
| Snapshot compiler pins versions, member summary, field projection, filter, watermark | `DeliveryService.compile`, `Delivery` |
| Withdrawal propagator stops future queries, applies pre-registered retention/destruction | `RevocationService`, `DispositionRule`, `DispositionExecution`; historical delivery summary kept |
| Recovery verifier: gaps, duplicate re-sends, cross-domain digest agreement | `RecoveryService.run` → `RecoveryCheck` |
| Two reconstruction routes producing identical legal sets | `EligibilityRunService` (MATERIALIZED vs REPLAY) + equivalence test; both call one engine |
| Stale snapshots never authorise a new read; old deliveries not pretended erased | `invalidateRuns`, `Delivery.accessRevoked` with summary/digests intact |
| Retransmission after restore not double-counted | `EventIngestService` transport-identity dedupe + `TransportReceipt` |
| Withdrawal/correction race adjudicated by approved rule, else quarantined | `EventIngestService` + `TieBreakRule` + `QuarantineItem` |

### Reconstruction routes

* **MATERIALIZED** — `POST /api/runs/materialize?day=16` evaluates once and persists one row per candidate. Later
  consent/protocol revisions mark affected runs `STALE`. Queries read the stored rows directly; a stale run is
  never consulted to authorise a read.
* **REPLAY** — `GET /api/runs/replay?day=16` rebuilds from the append-only ledgers at the chosen instant,
  persisting nothing; it requires the watermark completeness proof returned alongside.

Both call the same pure `EligibilityEngine`. The acceptance check `ALL/EQ` and `GET /api/runs/compare?day=16`
assert the two routes produce the same legal membership, verdicts and reason codes. At day 16 both report
**13 legal rows, 0 differences**.

---

## 3a. Consent material proof-chain workbench

The workbench (`/proof` in the console, API prefix `/api/proof`) is deliberately independent of the eligibility
"agreed" flag. Five screens map to the five inspection artefacts:

| Screen | Backend component | What it proves |
|--------|-------------------|----------------|
| 模板时间线 | `ConsentTemplate` + `ConsentTemplateRepo` | Publication intervals per **centre / language / subject age band / protocol revision**, field-summary hash and explicit revoked/superseded status. A replacement closes the old interval instead of overwriting it. |
| 材料证明链 | `SignatureWitness`, `AttachmentVersion`, `ProofChainService` | Local-domain signer/CA thumbprints, terminal integrity statement, signed/witnessed/uploaded clocks, attachment hash, nurse review and guardianship proof. Only pseudonyms and local handles are stored — identity plaintext never leaves the residency domain. |
| 版本差异 | `ProofChainService` diff-er + `CommitmentService` | Classifies every attachment-version pair as 合法补充 / 不可验证替换 / 重复上传, plus 身份关联异常 for shared guardianship evidence; hosts the appendable commitment tree with the extra leaf-state index for revocations/re-uploads. |
| 抽检覆盖 | `SamplingPlannerService` | Minimum review sample per centre/device model/anomaly pattern (attribute sampling) and the distortion family the sample distinguishes; a failed batch attestation widens uncertainty and retains prior conclusion versions. |
| 本地域核验结论 | `ProofConclusion` + `WitnessGraphEdge` | The three statuses with the exact local evidence each depends on, conclusion version history, and the guardian/device/nurse witness graph with broken edges. |

### The three proof statuses and their rules

* **VERIFIED_SIGNATURE (可验证签署)** — template interval covers the local signing instant, the certificate chain
  and attestation match, the attachment hash is intact, nurse review and guardianship evidence are present.
* **MATERIAL_CORRUPTED (材料内容破损)** — hash changed without a continuous chain/explanation, the certificate
  chain breaks, or the terminal attestation failed.
* **IDENTITY_OR_TIME_PENDING (身份或时间待核)** — signing time vs template publication unresolved, shared
  guardianship link, missing nurse review, supplement awaiting review, no local witness, or batch-level
  uncertainty. A material lacking local witness lands here: it is **never** auto-downgraded to a withdrawal and
  **never** treated as a valid consent.

### Mandatory inspection scenarios (all seeded and test-covered)

1. **Offline signature earlier than template publication** (`SIG-E5-001`): the tablet's trusted time comes from a
   later clock re-synchronisation, so the compiler emits `IDENTITY_OR_TIME_PENDING` with
   `TEMPLATE_NOT_PUBLISHED_AT_SIGNING,TRUSTED_TIME_RESYNCED,UPLOAD_BACKFILL_FORBIDDEN`. The upload instant is
   never used to back-fill the signing point.
2. **Two pseudonyms, one guardianship proof, overlapping windows** (`SIG-F6-001` / `SIG-G7-001`): the diff-er
   raises `IDENTITY_LINK_ANOMALY` naming both pseudonyms; the materials stay separate and are never merged.
3. **Hash changed, chain continuous, nursing supplement note** (`SIG-C3-002` v1→v2): classified
   `LEGITIMATE_SUPPLEMENT`; the original v1 material is retained and the new version is listed for review
   (`SUPPLEMENT_PENDING_REVIEW`). The same table also demonstrates a `DUPLICATE_UPLOAD` (`SIG-C3-001`, identical
   hash, counted once) and an `UNVERIFIABLE_REPLACEMENT` (`SIG-B2-002`, unknown CA, no note → corrupted).
4. **Terminal batch attestation failure** (batch `TAB-CN02-B7`): the sampling result widens the proof-uncertainty
   scope to all four batch materials, re-issues a batch-wide plan, and keeps the original audit conclusion
   versions (rows are superseded, never deleted).

### Bulk-proof scheme choice

* **Appendable commitment tree (default for larger centres)** — signing hashes fold per centre/day into a
  Merkle-style root (`/api/proof/batches/fold`) giving cheap batch existence proofs
  (`/api/proof/batches/{code}/proof`). Revocation/re-upload keeps the original leaf hash and adds a leaf-state
  index line; the sealed root never changes.
* **Per-document cross-signature witness graph (smaller/forensic sites)** — guardian/device/nurse edges per
  signature trace exactly where a relation breaks, at higher storage and batch-audit cost. Each witness elects
  its scheme, so the two coexist by centre scale and on-site forensics needs.

Key endpoints: `GET /api/proof/{summary,templates,witnesses,conclusions,diffs,plans,batches,graph}`,
`POST /api/proof/{compile,diff}` (optional `?signatureNo=`), `POST /api/proof/witnesses`,
`POST /api/proof/witnesses/{no}/versions`, `POST /api/proof/plans`, `POST /api/proof/plans/results`,
`POST /api/proof/batches/{fold,annotate}`, `POST /api/proof/graph/build`. Write operations require ADMIN or
SPONSOR; every GET is available to all five roles.

---

## 4. The acceptance scenario (analysis day 16)

Study day 1 = `2026-03-02T09:00Z`; analysis instant for day N = `23:00Z` on that day.
Sites: `CN-01` Shanghai (healthy), `CN-02` Wuhan (link stalled), `SG-01` Singapore (healthy).

| Subject | Timeline | Required outcome |
|---------|----------|------------------|
| **A** | signs China-only consent D10; visit occurs D11; uploaded D15 | **not** global-available (reason = consent scope, not upload delay); locally available |
| **B** | AE occurs D11; withdraws global purpose D12 (signed 09:00, received 20:00); new visit D13 | D11 AE held under restricted retention but reuse globally blocked; D13 visit has no basis; local use continues |
| **C** | protocol v2.0 approved D13, effective D14 at CN-01; imaging D13 and D15 | D13 under v1.2; D15 under v2.0 carrying `IMAGING_ADC`/`IMAGING_TUMOR_VOL`; v2.0 never applies to other sites |
| **D** | CN-02 link stalled after D9; randomisation D5, visit D12 | D5 globally available; D12 **PENDING / UNCONFIRMED** globally, locally available; gap shown, not zero |

If a historical delivery contains B's D11 row (e.g. compile at day 11), propagating B's withdrawal revokes
access to it immediately and applies `DR-RETAIN-25Y` (restricted retention) while the delivery's frozen member
summary and digests stay intact for audit — the delivery is not pretended to have been erased.

---

## 5. Verification (what was actually run here)

All commands below were executed in this workspace against a live instance. Results are quoted verbatim.

### 5.1 Backend integration tests — 26/26 pass

```bash
cd backend && mvn test
```
```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in com.ctrial.AcceptanceRulesTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in com.ctrial.ProofChainRulesTest
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Covers: A/B/C/D rules; intersection semantics; both reconstruction routes agreeing (read-only and persisted);
stale-snapshot invalidation; transport dedupe; conflicting-duplicate quarantine; withdrawal/correction race
quarantine; delivery projection has no identity fields and survives revocation as an intact summary; **plus the
proof-chain rules** — verified signatures with local evidence, offline/time-resync pending without back-fill,
guardian identity-link anomaly without merging, legitimate supplement vs duplicate vs unverifiable replacement,
no-local-witness pending (never withdrawal/auto-valid), batch attestation failure widening scope while retaining
prior conclusion versions, Merkle inclusion proofs and witness-graph edges.

### 5.2 Frontend render tests (live backend) — 9/9 pass

```bash
cd frontend && npm run test        # vitest + happy-dom, mounts the real views against http://127.0.0.1:8080
```
```
Test Files  1 passed (1)
     Tests  9 passed (9)
```
Asserts the Overview shows **15/15**, the Acceptance cards list A1/B1/C1/D2, the Eligibility pivot renders both
planes and subject D as Pending, and the Consent / Deliveries / Watermarks views render without error; the two
new tests mount the proof-chain workbench, assert all five tabs and the three statuses, and open the sampling
tab to confirm the widened `BATCH_ATTESTATION_FAILURE` result for batch `TAB-CN02-B7`.

### 5.3 End-to-end API acceptance (live jar)

| Step | Result |
|------|--------|
| `GET /api/acceptance?day=16` | **15/15 acceptance checks pass** (A1–A3, B1–B5, C1–C3, D1–D3, EQ) |
| `POST /api/deliveries/compile {day:11}` | `DLV-D11-001`, 2 members incl. `PSN-B2`, digest `a5110f88…` |
| `POST /api/deliveries/compile {day:16, route:REPLAY}` | `DLV-D16-001`, 4 members (C×3, D randomisation) |
| `POST /api/revocations/propagate {consentEntryId:3}` | 2 snapshot runs invalidated, 1 delivery access-revoked, 1 row restricted under `DR-RETAIN-25Y` |
| `GET /api/deliveries/DLV-D11-001` after revocation | `readable=false`, `status=ACCESS_REVOKED`, content digest intact, 2 summary lines kept |
| `GET /api/deliveries/DLV-D16-001` | `readable=true`, `status=ACTIVE` (unaffected) |
| `POST /api/recovery/run {site:CN-02}` | `status=FINDINGS`, 2 gaps, 0 duplicate resends, 0 digest mismatches |
| exact re-send of `VST-D4-V1` seq 12 | `duplicate=true` — "Retransmission … acknowledged; counted once." event count stays 9 |
| conflicting re-send (different payload) | `duplicate=true, quarantined=true` → `Q-0001-DUP` |
| `GET /api/watermarks?day=16` | CN-02 source confirmed 12 / ack 9 / analysis 9 → D12 **UNCONFIRMED** |

The pinned watermark string for day 16 is
`CN-01:AE=15(src=15,ack=15); … CN-02:VISIT=12(src=12,ack=9)…` (the CN-02 analysis stays at 9).

### 5.4 Proof-chain workbench end-to-end (live jar)

| Call / check | Result |
|------|--------|
| `GET /api/proof/summary` | 11 materials: **3 verified, 1 corrupted, 7 pending**, 4 open diffs, 2 active plans, 3 commitment batches |
| `GET /api/proof/witnesses` (statuses) | `SIG-E5-001` pending with `TEMPLATE_NOT_PUBLISHED_AT_SIGNING,TRUSTED_TIME_RESYNCED,UPLOAD_BACKFILL_FORBIDDEN`; `SIG-A1-002` pending `NO_LOCAL_WITNESS`; `SIG-B2-002` `MATERIAL_CORRUPTED`; `SIG-C3-002` pending `SUPPLEMENT_PENDING_REVIEW`; `SIG-D4-001` pending `BATCH_ATTESTATION_UNCERTAINTY` |
| `GET /api/proof/diffs` | `SIG-F6-001 IDENTITY_LINK_ANOMALY (PSN-F6,PSN-G7)`; `SIG-C3-002 LEGITIMATE_SUPPLEMENT`; `SIG-B2-002 UNVERIFIABLE_REPLACEMENT` (chain break); `SIG-C3-001 DUPLICATE_UPLOAD` |
| `GET /api/proof/plans` | Routine CN-01 plan (population 5, sample 5); CN-02 batch plan superseded by `SP-WIDEN-TAB-CN02-B7-3` covering all 4 batch signatures |
| Sampling result `SR-1-CN-02` | `attestationFailure=true`, widened `SIG-F6-001,SIG-G7-001,SIG-D4-001,SIG-I9-001`, retained prior conclusion refs e.g. `SIG-D4-001:2(VERIFIED_SIGNATURE)` |
| `GET /api/proof/batches` + inclusion proof | Three sealed Merkle batches; inclusion proof for `SIG-A1-001` returns `included=true, rootMatches=true` with 3 sibling hashes; leaf-state index records revoked/replaced leaves while the root hash is unchanged |
| `GET /api/proof/graph` | 15 nodes / 6 edges for the two witness-graph signatures (guardian + device + nurse each), shared guardian visible, no broken edges in the seeded graph |
| Identity residency | No proof endpoint response contains a local identity (`CHN-…` / `SGP-…` handles) — verified by grep over witnesses, detail, conclusions, templates, diffs and graph |
| RBAC | `auditor` and `china` receive **403** on `POST /api/proof/**`; auditor GET works (200) |
| Re-compile | `POST /api/proof/compile?signatureNo=SIG-A1-001` appends a new conclusion row; the previous row is retained with `superseded=true` |
| Regression | `GET /api/acceptance?day=16` still reports **15/15 pass** — the workbench never mutates the consent ledger |

---

## 6. Using the console

Open `http://localhost:8080` and sign in (the login screen lists the demo accounts; click one to prefill).

* **Overview** — acceptance score, watermark gaps, deliveries, subjects.
* **Acceptance** — the four subject cards with expected vs observed and reason codes.
* **Consent & protocol** — append grants/withdrawals and protocol versions; per-subject timelines; approved
  envelopes. Recording a withdrawal invalidates affected snapshot runs immediately.
* **Eligibility** — the dual-plane pivot (replay or materialised), with a live route-equivalence badge and the
  source event ledger. The top-bar **analysis day** changes the instant for every screen.
* **Watermarks** — per-domain three-counter bars with an UNCONFIRMED gap band; confirm source / ack transfer /
  fold analysis / break & restore link; run a recovery check.
* **Deliveries** — compile by either route, inspect the pinned envelope, frozen member summary and projected
  records; revocation state is visible.
* **Withdrawal** — propagate a recorded withdrawal, register disposition and tie-break rules, see executions.
* **Recovery** — recovery reports (expand for per-domain digests), simulate a post-restore re-send, resolve
  quarantined races.
* **Proof chain** — the consent material proof-chain workbench with five tabs: 模板时间线 (template registry
  intervals), 材料证明链 (witness ledger with per-material evidence chain, attachment re-upload and inclusion
  proof), 版本差异 (diff classifications and commitment-tree folding/leaf annotations), 抽检覆盖 (sampling
  plans, result entry and batch-failure widening) and 本地域核验结论 (the three statuses with local evidence and
  the guardian/device/nurse witness graph).
* **Audit** — the append-only audit trail of every regulator-relevant transition.

### Try the headline flow

1. As **global**, Deliveries → set analysis day `11` → *Compile delivery* (contains B's D11 AE).
2. As **admin**, Withdrawal → *Propagate* B's withdrawal.
3. Back in Deliveries → open `DLV-D11-001`: content is revoked, but the frozen summary and digests remain.
4. Set day `16`, Acceptances → all checks pass; Eligibility → D's D12 visit is Pending globally, available locally.

---

## 7. Design decisions worth noting

* **One engine, two routes.** Materialisation is a cache over the same `EligibilityEngine`; it can never diverge
  because it is not a second implementation. Snapshots are `STALE`d on later changes and are refused for new
  reads, so an old delivery is never a standing authorisation.
* **Intersection, literally.** Every gate must pass; a single failing gate blocks, a single undeterminable gate
  pends. There is no union anywhere in the evaluator.
* **Two clocks, on purpose.** All validity uses the local signature/withdrawal instant; the receipt instant is
  only an ordering tie-breaker. B's withdrawal is effective at the moment it was signed, not when it reached us.
* **Gaps are first-class.** The analysis watermark can never fold past the transfer acknowledgement, and anything
  above it is `PENDING`, never reported as absent. The UI draws the unconfirmed band instead of a zero.
* **History is preserved.** Revoking a delivery sets `accessRevoked` and applies the registered disposition, but
  the legal-member summary and digests are frozen and kept. Nothing claims the delivery never existed.
* **Isolate, don't guess.** A withdrawal/correction race with equal effective instants and no registered
  tie-break rule is quarantined; it never silently picks a side.
* **Provenance is a separate question from the consent flag.** The proof-chain workbench never writes the consent
  ledger. "Agreed" may be true for eligibility while the material is still `MATERIAL_CORRUPTED` or
  `IDENTITY_OR_TIME_PENDING`; missing local witness evidence causes a pending proof, never an automatic
  withdrawal or an automatic valid consent.
* **Clocks and hashes are evidence, not convenience.** Upload time never back-fills a signing instant; a hash
  change with a continuous certificate chain and a nursing note is a retained supplement, while a broken chain is
  corruption. All proof conclusions, diffs and sampling outcomes are append-only versions — re-evaluation
  supersedes a row but never deletes it, and a commitment-tree root is immutable with leaf state carried in a
  separate index.

---

## 8. Project layout

```
backend/
  src/main/java/com/ctrial/
    model/        JPA entities + Enums (the rule vocabulary), incl. ConsentTemplate, SignatureWitness,
                  AttachmentVersion, ProofConclusion, DiffFinding, SamplingPlan/Result, CommitmentBatch,
                  WitnessGraphEdge
    repo/         Spring Data repositories for the proof-chain tables and the existing ledgers
    service/      EligibilityEngine (pure), EligibilityService, EligibilityRunService,
                  DeliveryService, RevocationService, RecoveryService, EventIngestService,
                  StateService, AcceptanceService, AuditService, ProofChainService,
                  SamplingPlannerService, CommitmentService
    web/          ReadController, CommandController, ProofChainController, SpaConfig
    config/       SecurityConfig, DemoSeeder (base scenario + proof-chain scenario)
  src/test/java/com/ctrial/AcceptanceRulesTest.java, ProofChainRulesTest.java
frontend/
  src/            App, views/* (incl. ProofChain.vue), components/proof/* (five workbench tabs),
                  lib/api.js, lib/store.js, ui.test.js
Dockerfile, compose.yaml
```
