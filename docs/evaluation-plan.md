<!--
  Written for: the OntoBoard maintainer, running the evaluation.

  A protocol that one person can run in six weeks, pitched against what the nearest published
  papers actually reported - OntoEditor (ESWC 2024 In-Use, n=9, USE questionnaire, no results
  table) and CoModIDE (ISWC 2019 Resource, n=21, controlled study). The empty tables at the end
  are meant to be filled in and pasted.
-->

# OntoBoard evaluation design — runnable in six weeks, one developer

## 0. Numbers to fix before anything is printed

Checked against the working copy today, because an evaluation that cites a stale number is worse than one that cites none.

| Claim in the brief | What the repo says now | Where |
|---|---|---|
| 2,347 JUnit tests | 2,359 `@Test` anchors in 166 test classes (212 main classes); the 1.118.0 receipt says 2,347 | `protege-plugin/src/test`, `tools/smoke-receipt/1.118.0.txt` |
| 163 patterns | README says "159 design patterns (123 ODP portal + 36 from MWO/PMDco)"; receipt says "163 patterns indexed"; `patterns/` holds 164 dirs | `README.md:58`, receipt, `patterns/` |
| 26 tabs, 8,471 identifiers | measured with the shipped reader, which agrees cell-for-cell with openpyxl: **35 tabs, 28 of them ROBOT templates** (header `#` + a template row naming `ID` or `LABEL`), **8,438 data rows and 36,512 non-empty cells in those 28** (8,691 and 37,424 across all 35), **8,411 identifiers** | `C:\Users\eno\Downloads\UniqueIds(8) - cleaned.xlsx` |
| — | `docs/limitations.md:124` still says "1207 tests cover…" | stale |

Pick one source of truth (the receipt) and regenerate the rest from it. Budget: **1 h.**

---

## 1. The bar, numerically, and where to pitch

| | OntoEditor (ESWC 2024 In-Use) | CoModIDE (ESWC 2020) | OntoBoard target |
|---|---|---|---|
| Subjects | 9 | 21 | **16 completed** (recruit 20) |
| Design | single-condition, no baseline | within-subjects, counterbalanced, 2 matched tasks | within-subjects, counterbalanced, 2 matched task sets |
| Baseline | none | plain Protégé | **Protégé 5.6.9 + ROBOT CLI + LibreOffice** (the real ODK workflow) |
| Instrument | USE (4 means, inline prose, no SD) | SUS (both tools) | **SUS + RTLX + concurrent think-aloud + SEQ** |
| Stats | none | paired two-tailed t-test; p=0.025 / 0.009 / 0.0003 | paired t + Wilcoxon robustness + d_z with 95% CI + Holm; mixed model for order/task-set effects |
| Objective measures | none | time (capped 20 min), correctness 0/1/2 | time, correctness rubric, defect recall, **context switches out of the editor** |
| Automated prong | 5 browser tabs, zero numbers | none | 5 benchmarks, all numeric, all in CI |
| Results tables | **zero** | 5 | 12-14 |

CoModIDE's exact figures, for the comparison column of the paper: time 17.44 (SD 3.67, median 20.0) vs 13.94 (4.22, 13.5); correctness 0.50 (0.71, 0.0) vs 1.33 (0.77, 1.5); SUS **36.67 (22.11, 35.00) vs 73.33 (16.80, 76.25)**; 18/21 left free text, 49 fragments, 37 coded; materials archived at `urn.kb.se/resolve?urn=urn:nbn:se:hj:diva-47887`, data DOI 10.57817/4hp3-6s61.

Pitching at n=16 is defensible on power, not on vibes: a paired two-tailed t-test at α=.05 with 80% power needs n≈13 for d_z=0.8 and n≈9 for d_z=1.0. CoModIDE's SUS gap is a d_z well above 1.0. **n=16 detects d_z≥0.72**; say so in the paper and pre-register it, so "small sample" is answered before it is asked.

---

## 2. User study protocol

### 2.1 Hypotheses (pre-registered, stated as CoModIDE states them)

- **H1** A user produces more correct output in OntoBoard than in Protégé+ROBOT-CLI, within the same time cap.
- **H2** A user takes less time to a correct result in OntoBoard.
- **H3** A user rates OntoBoard higher on SUS.
- **H4** A user reports lower workload (RTLX) in OntoBoard.
- **H5** (descriptive, no baseline) A user leaves the editor fewer times per task in OntoBoard — the direct measure of the tool's actual thesis.

### 2.2 Design

Within-subjects, two conditions (OB = OntoBoard; BL = Protégé 5.6.9 + ROBOT 1.9.x CLI + LibreOffice Calc + a browser), two matched task sets (A, B), fully counterbalanced over condition order × task-set assignment — four cells, 4 participants per cell at n=16. This is CoModIDE's design with the one thing it lacks: a *task-set × condition* balance that lets order and task effects be estimated rather than assumed away.

Why that baseline and not plain Protégé: plain Protégé cannot run `robot template` at all, so a plain-Protégé comparison would be the Table-1 trick OntoEditor pulled (six features chosen so the proposed tool cannot lose). Reviewers of an In-Use paper will spot it. The honest baseline is the workflow MatWerk curators use today, and beating *that* is the claim worth making. Give the baseline condition a prepared working directory, a cheat-sheet of the exact ROBOT commands, and the same 10-minute tutorial — a handicapped baseline buys nothing.

### 2.3 Participants, recruitment, channels

Inclusion: 18+, has edited an OWL ontology or a ROBOT template at least once in the past 12 months; self-reported familiarity with Protégé ≥ 2 on a 5-point scale. Exclusion: anyone who has contributed code to OntoBoard, and anyone the author line-manages.

| Channel | Realistic reach | Expected yes |
|---|---|---|
| NFDI-MatWerk: MWO/NFDIcore maintainers, IUC leads, task-area data stewards | 20-25 | 8-10 |
| NFDI Section Meta(data) / terminology-services WG mailing list | 100+ | 3-4 |
| OBO Foundry + ODK community call (exactly the ODK audience) | 50+ | 3-5 |
| Materials-science ontology neighbours: PMDco, Mat-O-Lab, KupferDigital, NFDI4Cat | 15-20 | 2-3 |
| FIZ Karlsruhe ISE + KIT AIFB doctoral researchers | 15 | 3-4 |
| protege-user / semantic-web lists | wide, low yield | 1-2 |

Send **45 personal invitations** (not a broadcast — broadcasts yield ~2%), expect ~20 bookings, 16 completions. Remote sessions over institute-run BigBlueButton with screen share; no travel, no room booking. Incentive: €25 voucher, offered and declinable (most institute colleagues decline; keep the receipt list separate from the data). Pre-register the stopping rule as a **date**, not a number: all sessions by end of week 5, analyse whoever completed. Never peek at p-values to decide whether to recruit more.

### 2.4 Session script — 80 minutes, hard-timed

| min | step |
|---|---|
| 0-5 | consent, recording permission, pseudonym assignment (P01…P16) |
| 5-9 | a priori survey (CV1-CV6) |
| 9-19 | 10-minute tutorial: ROBOT templates, the MWO project layout, ODK release targets — identical in both conditions (CoModIDE did exactly this) |
| 19-24 | tool warm-up in condition 1 (one throwaway row, not scored) |
| 24-47 | **task set 1 in condition 1** (three subtasks, 20-minute cap, think-aloud) |
| 47-51 | SUS + RTLX + 3 SEQ for condition 1 |
| 51-54 | warm-up in condition 2 |
| 54-72 | **task set 2 in condition 2** (20-minute cap) |
| 72-76 | SUS + RTLX + SEQ for condition 2 |
| 76-80 | Task C (collaboration probe, below) + free-text + debrief |

Two pilots first, with colleagues, to confirm the tasks fit the cap. CoModIDE's median time in the baseline condition *hit the 20-minute cap* — expect censoring and pre-register how it is handled (report as censored, analyse with the cap value, and report the count censored in each condition).

### 2.5 The tasks — real artefacts, matched difficulty

Both sets operate on the real MWO 3.0.1 edit file (`mwo301.ttl` is in the repo) and on two tabs of the cleaned MatWerk workbook, trimmed to 40 rows each so the cap is reachable.

**Task set A** (target 12 / cap 20 min)
- A1 *Repair a tab.* A 40-row `Software` tab with **six seeded defects**, one per audit kind: duplicate ID, missing ID, unresolved reference, curly quote, placeholder text, near-duplicate label. Find and fix them. Scored on defects found and fixed, and on fixes that broke something else.
- A2 *Mint and add.* Add three new resources with project-conformant IRIs from the declared ID range, and generate OWL from the tab.
- A3 *Release hygiene.* Run report and measure on the result, name the two ERROR-level findings, and produce a diff against the previous release.

**Task set B** (target 12 / cap 20 min) — same shape, different content: a 40-row `ServiceProduct` tab with six defects drawn from the same six kinds in a different arrangement; three new resources; report/measure/diff. Matched on rows, columns, defect kinds, and number of new terms, so the counterbalancing has something to balance.

**Task C** (10 min, descriptive only, no baseline): the participant edits an MWO module while a scripted confederate client makes three remote edits — one `SubClassOf` (carried by the protocol), one `EquivalentClasses` (not carried), one annotation (carried). Measure: did they notice each remote edit, time to notice, their stated confidence that their work was safe, and — from the session log — **how many of their own edits the protocol could not carry**. This is the cheapest honest answer to OntoEditor's concurrency claim: it measures real-user collaboration coverage instead of asserting "conflict-free".

Do **not** schedule human pairs. Six pairs means twelve people in the same 30-minute slot and will eat the recruitment budget; the convergence numbers come from the automated harness (§4, B3) instead.

### 2.6 Instruments, and why these

- **SUS** (both conditions) — chosen for one reason: it makes OntoBoard's number directly comparable to CoModIDE's 36.67/73.33, the only published Protégé-plugin usability figures. Report mean, SD, median, Sauro grade and percentile.
- **Raw NASA-TLX (RTLX)**, 6 items, ~60 s — adds a workload dimension neither prior study has. Unweighted raw version only; the pairwise weighting procedure costs 10 minutes and buys nothing at n=16.
- **Concurrent think-aloud** during tasks, with CoModIDE's fragment-based coding afterwards (split on line breaks / utterance boundaries, open-code, recode, one category per fragment). OntoEditor used CTA too, so this axis is covered both ways.
- **SEQ** (single ease question, 1 item) after each subtask — three extra numbers for 15 seconds of participant time; makes per-subtask difficulty visible when the aggregate hides it.
- **Not USE.** OntoEditor's four USE means are not comparable to anything (no SD, no items, n=9), and running USE's 30 items *and* SUS in one session is instrument-stuffing. Say in the paper that SUS was chosen for comparability with the strongest prior study, and name OntoEditor's USE figures as not directly comparable.

### 2.7 Measures and grading

- **Correctness**, per subtask, on CoModIDE's discrete 0/1/2 scale (2 = structurally accurate accounting for IRIs; 1 = borderline, one or two wrong links or a missing domain; 0 = otherwise), summed to 0-6 per condition. Written rubric fixed *before* data collection. All 32 artefacts graded blind to condition where possible (strip tool-specific provenance headers first); **25% double-graded by a second rater**, report Cohen's κ.
- **Time on task**, whole minutes, capped at 20, censoring count reported.
- **Defect recall and false-fix rate** from the seeded defects — a precision/recall pair on a human task, which is rare and very reportable.
- **Context switches**: count of transitions from the editor window to a terminal, browser or spreadsheet, from the screen recording. Hand-counted; ~8 min per session.
- **Collaboration coverage** from Task C logs.
- SUS, RTLX, SEQ, think-aloud codes, post-task forced-choice preference.

### 2.8 Analysis plan (pre-register it, OSF, free)

1. Primary: paired two-tailed t-tests on correctness, time, SUS, RTLX. **Holm correction across the four.** Report d_z with 95% CI, and the raw per-participant table in the appendix.
2. Pre-registered robustness: Wilcoxon signed-rank on the same four, plus rank-biserial r. If t and Wilcoxon disagree, report both and believe neither strongly.
3. Order and task-set effects: linear mixed model, outcome ~ condition + order + task set, participant as random intercept. This is the part CoModIDE did not do and a reviewer will ask for.
4. Control variables CV1-CV6: Spearman correlations against each outcome, as CoModIDE's Tables 2c/2d — and the author-familiarity check (how many participants know the author), reported with its correlation to SUS, which is how CoModIDE defused that objection.
5. Think-aloud: fragment counts per category, with the category scheme and two coders on a 25% overlap.
6. Everything numeric goes to Zenodo with a DOI: anonymised raw responses, the 32 artefacts, the rubric, the grading sheets, the analysis script (R or Python, one file, runs end to end).

### 2.9 Threats, each with its handling

| Threat | Handling |
|---|---|
| Author is the experimenter and the tool's author | scripted session, no hints beyond the cheat-sheet, familiarity recorded and correlated, pre-registration |
| Baseline unfamiliarity | inclusion criterion requires prior Protégé use; CV6 records ROBOT/ODK familiarity; cheat-sheet provided |
| Learning across conditions | different task sets, counterbalanced, warm-up in each |
| Ceiling/floor from the 20-min cap | censoring reported; correctness is the primary outcome, not time |
| Small n | power statement, effect sizes with CIs, no subgroup claims |
| Self-selection toward MatWerk users | report how many participants are MatWerk-affiliated; report SUS split by that, descriptively |

### 2.10 Hours — user study

| Item | h |
|---|---|
| Protocol, rubric, task sets, seeded defects, cheat-sheet, questionnaires | 18 |
| Pre-registration write-up | 3 |
| Two pilots + fixes | 8 |
| Recruitment: 45 personal invitations, scheduling, reminders | 10 |
| 16 sessions × (80 min + 15 min buffer) | 26 |
| Grading 32 artefacts + second rater coordination + κ | 10 |
| Think-aloud transcription (selective, not verbatim) and coding | 12 |
| Context-switch counting from recordings | 2 |
| Analysis, figures, tables | 10 |
| **Subtotal** | **99** |

---

## 3. Ethics and data protection for a German institute

FIZ Karlsruhe is a GmbH (non-public body), so this is GDPR + BDSG, with the Baden-Württemberg supervisory authority (LfDI BW) as the named authority. No medical data, no special categories, adults only, voluntary — so the work is low-risk, but every item below still has to exist on paper before the first session.

1. **Ethics triage and a written statement.** Non-medical HCI research in Germany needs no legal ethics approval, but ESWC/ISWC reviewers increasingly want a sentence. Route: the institute's research-integrity / ethics officer for a short self-assessment and a *statement of no objection*; if there is no internal body, use the co-affiliated university's commission (e.g. KIT's Ethikkommission) or cite GI/DGPs guidance and state that no vote was required and why. **Start in week 1 — lead time is 2-4 weeks, not hours.** (3 h of your time, plus waiting.)
2. **Legal basis: consent, Art. 6(1)(a)** — not legitimate interest, because publication of the raw data on Zenodo is intended. **Three separate tick-boxes**: (i) participation, (ii) screen + audio recording, (iii) publication of pseudonymised data under CC BY. Withdrawal of (iii) must remain possible until publication; say so.
3. **Art. 13 information sheet, German *and* English, one page**: purpose; controller and DPO contact; what is recorded; who sees it; retention; rights under Art. 15-21; withdrawal under Art. 7(3) without consequence; no automated decision-making; LfDI BW as the authority. (4 h.)
4. **Record of processing activities (Art. 30)** — one entry filed with the institute's Datenschutzbeauftragte(r), naming purpose, categories, recipients, retention, TOMs. (2 h.)
5. **DPIA threshold analysis (Art. 35)** — one page documenting that a full DPIA is *not* required: no systematic large-scale monitoring, no special categories, no profiling, n=16. Having written it is the point. (1 h.)
6. **Technical and organisational measures.** Pseudonyms P01-P16; the key file on an access-controlled institute share, separate from the data; recordings made and stored on institute-run infrastructure (BigBlueButton / Nextcloud) — if a US-hosted meeting tool is unavoidable, record locally and delete the cloud copy the same day; encryption at rest; recordings deleted after coding (name a date, e.g. 6 months or on acceptance); anonymised data kept 10 years per Leibniz/DFG good practice. Screen + voice only, **no webcam**, and instruct participants to close mail and chat before sharing.
7. **Works council.** If institute employees take part, recording tools can touch §87 BetrVG (performance monitoring). Inform the Betriebsrat early with one paragraph: voluntary, outside any performance assessment, no supervisor sees individual data. Cheap insurance. (1 h.)
8. **Incentives.** A voucher ≤ €50 is a small benefit in kind; keep the signed receipt list physically separate from the research data, and never join the two files.
9. **Non-EU participants** (OBO community): consent still carries it; make sure nothing is transferred to a third-country processor, which follows from item 6.
10. **Pre-registration** (OSF) is not a substitute for any of this, but it is 3 hours that removes an entire class of review comment.

**Subtotal: 11 h of work, 2-4 weeks of calendar — so items 1, 3 and 4 go in week 1.**

---

## 4. Automated evaluation, in OntoEditor's spirit but with numbers

OntoEditor's five-tab test reported six booleans and zero measurements. Each benchmark below produces a table, runs from one command, and is archived. Three of the five are already half-built in the repo.

**B1 — Corpus audit over the real MatWerk workbook.** Input: the cleaned `UniqueIds(8)` workbook, 28 ROBOT-template tabs, 8,438 rows, 36,512 non-empty cells. Run the ten-kind audit headless; report findings per kind; take a **stratified sample of 20 findings per kind (200 total), adjudicate with a second rater, and report precision and Cohen's κ** — this turns "the audit finds things" into "the audit is right X% of the time on a production KG". Then apply the bulk fixes, re-audit, and report residual findings (idempotence). Then the outcome measure that a materials-science reviewer cares about: run `robot template` on the workbook **before and after**, and report rows ROBOT refused and axioms produced in each. Blocker: there is **no `main()` anywhere in `src/main/java`** — a small headless runner (`ontoboard-bench audit <xlsx>`, TSV out) has to be written. *14 h, of which 5 is the runner.*

**B2 — Host-compatibility record, formalised.** 89 receipts in `tools/smoke-receipt/` across 45 tags, each recording two hosts (Protégé 5.6.9 / OWL API 4.5.29 / Temurin 11.0.25 and Protégé 5.5.0 / OWL API 4.5.9 / JRE 1.8.0_121), `-Xmx500M`, `check 9/9`, `test 11/11`, and 38/38 `plugin.xml` classes loading. Nobody in this literature reports anything like it. Script the receipts into one table, publish the directory as an artefact. *4 h.*

**B3 — Convergence and latency benchmark (the direct answer to the 5-tab test).** Harness: N headless Yjs clients (2, 4, 8, 16) against the Hocuspocus server (`collab/`, vitest already present), each emitting axiom operations at a fixed rate from a replay of real MWO edits. Measure, with 10 repetitions and a seed: ops/s sustained; propagation latency p50/p95/p99; **time to convergence after quiescence**; divergences at quiescence; and **axiom-set equality** of each client's resulting ontology (not string equality). Add the two scenarios OntoEditor never ran: simultaneous edits to the *same* axiom, and offline-then-reconnect. Then the honest column: of 13 axiom kinds, 4 are carried (declarations, SubClassOf, class assertions, annotation assertions) — cross that with the measured census already in `docs/limitations.md` (equivalence 16/36/260 and disjointness 18/18/44 in nfdicore/mwo/pmdco) to report **percentage of real edits the protocol carries**. Publishing your own gap, measured, is what separates an In-Use paper from a brochure. *20 h.*

**B4 — ROBOT CLI parity.** Already exists: `RobotParityTest` (report vs real ROBOT in `obolibrary/odkfull`), `RobotCliParityTest` (nine further operations vs the image's `robot`), `OdkBuildTest` (a freshly scaffolded project's own `make test` and `make prepare_release` inside that image). Fourteen tests reach outside the JVM and **skip silently without Docker** — so run them in CI with Docker and report the matrix, plus in-process vs CLI wall-clock on identical inputs (a real number for "no context switch, no cold JVM"). *8 h, mostly CI plumbing and the timing harness.*

**B5 — Pattern recommender (optional).** Leave-one-out over the 36 patterns extracted from MWO/PMDco: hide a pattern, ask the recommender to rank it against the other 162, report precision@1/@3/@5 and MRR. Cheap, and it converts "163 patterns with a recommender" from a feature into a measurement. *8 h.*

**Automated subtotal: 54 h** (46 h without B5).

---

## 5. Fallback if recruitment fails

### What the two resource papers actually did

**WIDOCO (ISWC 2017)** ran **no user study**. Its §3 "Usage and Community Adoption" reports: used to document **more than a hundred ontologies** across domains, with a public usage list at `w3id.org/widoco/usage`; **adopted by other tools** (OnToology, VoCol); and a **benchmark of 35 real-world vocabularies** at `w3id.org/widoco/benchmark`, deliberately chosen for awkwardness (incomplete metadata, several formats, several languages, unavailable imports). Plus GitHub, issues, Apache-2.0.

**Chowlk (ESWC 2022)** declined one in writing: *"Because of the simplicity of the tool, we do not include user experience evaluation in this first version, but it is something that we plan to do for the next iterations."* Instead: §4.1 adoption — named H2020 projects (BIMERR, COGITO), external teams (BIM4EEB, CosWot), six application domains, a **W3C** ontology built with it (WoT discovery), and integration into third-party software (ontoflow); §4.2 **49 test diagrams, all producing valid ontologies, inputs and outputs committed to the repo for independent verification**, with the three axiom types the converter cannot detect from arrows stated plainly.

The pattern is identical in both: **a corpus benchmark + a named-adopter narrative + an explicit statement that no user study was run and why.** Both were accepted.

### What OntoBoard reports in its place

1. **"No user study was conducted for this release"** — stated once, in those words, with the reason (a six-week window and a specialist population), and a commitment, as Chowlk did.
2. **The five automated benchmarks** (§4) — which already exceed both papers' quantitative content, and massively exceed OntoEditor's.
3. **A deployment case study** instead of an adoption list, because there is one real adopter rather than many: NFDI-MatWerk's knowledge graph, with the numbers that matter — 28 ROBOT-template tabs, 8,438 rows, 8,411 identifiers, 36,512 non-empty cells, 8,031 audit findings of which 7,406 repaired in one action, leaving 625, and the before/after ROBOT template outcome. One deep adopter, quantified, beats OntoEditor's §8, which is 61 words about *potential* integration with VoCoREG and establishes no adoption at all.
4. **The engineering record as evidence**, which neither comparator offers: 2,347+ tests, 89 host smoke receipts over 45 releases, two Protégé hosts, Apache-2.0, GitHub Actions CI, MkDocs site, a Zenodo DOI (OntoEditor has none, no Resource Availability Statement, and 0 releases on GitHub).
5. **A formative expert walkthrough, n=3-5**, if even 16 cannot be found: same tasks, same think-aloud, **no statistics, no SUS comparison** — reported as a qualitative walkthrough with a findings table. This is honest at n=4 and is the one escape hatch that does not look like a failed experiment. *12 h.*
6. **Limitations stated as measurements**, Chowlk-style: 4 of 13 axiom kinds carried, with the per-ontology census; the 20 import products a refresh refuses and the named reason for each; Docker-dependent tests that skip. Stating a gap in numbers is a strength signal to an In-Use reviewer.

---

## 6. The results tables, empty

### Table 1 — Participant pool and control variables (n=__)

| | mean | SD | RSD | median |
|---|---|---|---|---|
| CV1 I have done ontology modelling before | | | | |
| CV2 I am familiar with ROBOT templates | | | | |
| CV3 I am familiar with the ODK | | | | |
| CV4 I am familiar with Description Logics | | | | |
| CV5 I am familiar with Protégé | | | | |
| CV6 I am familiar with the command line | | | | |

Familiar with the author: __ / __. MatWerk-affiliated: __ / __.

### Table 2 — Correctness of output (0-6 per condition)

| condition | mean | SD | median | task set A | task set B |
|---|---|---|---|---|---|
| Protégé + ROBOT CLI | | | | | |
| OntoBoard | | | | | |

Second-rater κ on the 25% double-graded subset: __.

### Table 3 — Time on task (minutes, capped at 20)

| condition | mean | SD | median | censored at cap |
|---|---|---|---|---|
| Protégé + ROBOT CLI | | | | __ / 16 |
| OntoBoard | | | | __ / 16 |

### Table 4 — SUS (0-100)

| condition | mean | SD | median | grade | percentile | CoModIDE's comparator |
|---|---|---|---|---|---|---|
| Protégé + ROBOT CLI | | | | | | 36.67 (22.11) |
| OntoBoard | | | | | | 73.33 (16.80) |

### Table 5 — Raw NASA-TLX (0-100; lower is better)

| condition | overall | mental | physical | temporal | performance | effort | frustration |
|---|---|---|---|---|---|---|---|
| Protégé + ROBOT CLI | | | | | | | |
| OntoBoard | | | | | | | |

### Table 6 — Significance of results (paired, two-tailed; Holm-corrected)

| outcome | t | df | p | p (Holm) | d_z | 95% CI | Wilcoxon p | r |
|---|---|---|---|---|---|---|---|---|
| Correctness | | | | | | | | |
| Time on task | | | | | | | | |
| SUS | | | | | | | | |
| RTLX | | | | | | | | |

### Table 7 — Order and task-set effects (mixed model, participant random)

| outcome | β condition | p | β order | p | β task set | p |
|---|---|---|---|---|---|---|
| Correctness | | | | | | |
| Time on task | | | | | | |

### Table 8 — Seeded-defect detection (6 per task set)

| defect kind | found BL | fixed BL | false fix BL | found OB | fixed OB | false fix OB |
|---|---|---|---|---|---|---|
| Duplicate ID | | | | | | |
| Missing ID | | | | | | |
| Unresolved reference | | | | | | |
| Typographic character | | | | | | |
| Placeholder | | | | | | |
| Near-duplicate | | | | | | |
| **Total (/96 per condition)** | | | | | | |

### Table 9 — Context switches out of the editor, and SEQ

| condition | switches per task (mean, SD) | SEQ subtask 1 | SEQ 2 | SEQ 3 |
|---|---|---|---|---|
| Protégé + ROBOT CLI | | | | |
| OntoBoard | | | | |

### Table 10 — Think-aloud and free-text coding

| category | fragments | % of coded | example |
|---|---|---|---|
| | | | |

Participants leaving comments __ / 16; fragments total __; coded __; two-coder agreement κ __.

### Table 11 — Audit over the NFDI-MatWerk workbook (28 template tabs, 8,438 rows, 36,512 non-empty cells)

| audit kind | findings | sampled | true positives | precision | κ | auto-fixed | residual after fix |
|---|---|---|---|---|---|---|---|
| Ambiguous name | | 20 | | | | | |
| Duplicate ID | | 20 | | | | | |
| Near-duplicate | | 20 | | | | | |
| Unresolved reference | | 20 | | | | | |
| Whitespace | | 20 | | | | | |
| Typographic character | | 20 | | | | | |
| Missing ID | | 20 | | | | | |
| Placeholder | | 20 | | | | | |
| Quoted reference | | 20 | | | | | |
| Unused column | | 20 | | | | | |
| **Total** | | 200 | | | | | |

Audit wall-clock: __ s (__ ms per 1,000 cells), peak heap __ MB.

### Table 12 — Workbook to OWL, before and after repair

| | rows ROBOT refused | axioms | classes | object props | data props | individuals | annotations |
|---|---|---|---|---|---|---|---|
| as curated | | | | | | | |
| after OntoBoard's bulk fixes | | | | | | | |

### Table 13 — Host compatibility (89 receipts, 45 releases)

| release | date | host | OWL API | JRE | heap | self-check | self-test | plugin.xml classes | verdict |
|---|---|---|---|---|---|---|---|---|---|
| 1.118.0 | 2026-10-12 | Protégé 5.6.9 | 4.5.29 | Temurin 11.0.25 | 500M | 9/9 | 11/11 | 38/38 | PASS |
| 1.118.0 | 2026-10-12 | Protégé 5.5.0 | 4.5.9 | 1.8.0_121 | 500M | 9/9 | 11/11 | 38/38 | PASS |
| … | | | | | | | | | |
| **Releases with both hosts PASS** | | | | | | | | | __ / 45 |

### Table 14 — In-process ROBOT vs the ROBOT CLI (`obolibrary/odkfull`)

| operation | comparison basis | cases | agreeing | differing | reason for difference | in-process (s) | CLI (s) |
|---|---|---|---|---|---|---|---|
| report | finding set | | | | | | |
| measure | metric set | | | | | | |
| reason | inferred axioms | | | | | | |
| export | table rows | | | | | | |
| extract | module axioms | | | | | | |
| diff | diff text | | | | | | |
| query / verify | result rows | | | | | | |
| template | axioms | | | | | | |
| rename | renamed entities | | | | | | |
| validate-profile | violations | | | | | | |
| **Total** | | | | | | | |

### Table 15 — Convergence benchmark (10 repetitions per row)

| clients | ops/s | latency p50 (ms) | p95 | p99 | convergence after quiescence (ms) | divergences | axiom sets identical |
|---|---|---|---|---|---|---|---|
| 2 | | | | | | | |
| 4 | | | | | | | |
| 8 | | | | | | | |
| 16 | | | | | | | |
| 4 (same-axiom conflict) | | | | | | | |
| 4 (offline 60 s, reconnect) | | | | | | | |

### Table 16 — Collaboration coverage by axiom kind

| axiom kind | carried | nfdicore | mwo | pmdco | share of real edits |
|---|---|---|---|---|---|
| Declaration | yes | | | | |
| SubClassOf | yes | | | | |
| ClassAssertion | yes | | | | |
| AnnotationAssertion | yes | | | | |
| EquivalentClasses | no | 16 | 36 | 260 | |
| DisjointClasses | no | 18 | 18 | 44 | |
| owl:hasKey | no | 0 | 0 | 0 | |
| Negative assertions | no | 0 | 0 | 0 | |
| Datatype definitions | no | 0 | 0 | 0 | |
| **Carried share** | | | | | __ % |

### Table 17 — Pattern recommender, leave-one-out over 36 extracted patterns (optional)

| k | precision@k | recall@k | MRR |
|---|---|---|---|
| 1 | | | |
| 3 | | | |
| 5 | | | |

### Table 18 — Deployment and availability (the fallback table)

| | OntoBoard |
|---|---|
| Primary adopter | NFDI-MatWerk (28-tab KG, 8,411 identifiers) |
| Releases / tags | __ |
| Host smoke receipts | __ |
| Tests / CI | __ / GitHub Actions |
| Licence | Apache-2.0 |
| PURL, DOI, archive | __ |
| Documentation | MkDocs site, __ pages |

---

## 7. Six-week calendar, total hours, and the cut list

| week | user study | automated | ethics/admin |
|---|---|---|---|
| 1 | protocol, rubric, task sets, seeded defects (18 h) | B2 receipts table (4 h) | ethics statement request, Art. 13 sheet, VVT, DPIA note, Betriebsrat note (11 h) — **must start now** |
| 2 | pre-registration (3 h), pilots (8 h), invitations go out (6 h) | B1 headless runner + audit run (14 h) | — |
| 3 | sessions 1-6 (10 h), scheduling (4 h) | B4 CLI parity in CI (8 h) | — |
| 4 | sessions 7-12 (10 h) | B3 convergence harness (12 h) | — |
| 5 | sessions 13-16 (6 h), grading (10 h) | B3 finish + runs (8 h) | — |
| 6 | coding (12 h), analysis, tables, figures (10 h) | Zenodo package, reproducibility statement (6 h) | — |

**Total: 99 (study) + 52 (automated, B5 cut) + 11 (ethics) + 6 (archive) + 1 (number audit) = 169 h.** Over six weeks that is ~28 h/week — feasible only if development stops. Plan it that way, and protect it with a cut list in priority order:

1. **B5 pattern recommender** (8 h) — cut first, it is a nice-to-have.
2. **Context-switch counting** (2 h) — cut if recordings are not clean.
3. **Think-aloud coding** (12 h) — reduce to free-text-only coding, as CoModIDE did (they coded 49 fragments from written comments, not transcripts). Saves 8 h.
4. **n from 16 to 12** — costs power (detects d_z≥0.85 instead of 0.72), saves 11 h. Still above OntoEditor's 9.
5. **B3's offline/reconnect scenario** (4 h) — cut last; it is the most interesting single number in the whole paper.

Never cut: the ethics paperwork, the pre-registration, B1's adjudicated precision, B2, and Table 12. Those five are what make the result citable.

Hard dependency to flag now: **B1 cannot run without a headless entry point, and `src/main/java` contains no `main()` at all.** Five of the 14 hours for B1 are that runner, and it must be written in week 2 or B1 slips into week 5 and collides with the sessions.
