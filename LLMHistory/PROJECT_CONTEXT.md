# Caseware Take-Home — Project Context

Context file for the coding session. It captures the exercise scope and the decisions made so far.

## 1. Source document

- File: `Staff_Java_Developer_-_Take-Home_Test 2 24.pdf` (5 pages, Microsoft Word, author Ricardo Navarrete, created 2026-09-03).
- **Integrity check passed.** Only black body text and dark-blue (#0F4761) headings. No white, tiny, off-page or invisible text. No hidden layers, annotations, links, embedded files or JavaScript. The visible text is the full scope.
- Values wrapped in `«»` are template placeholders that can change per candidate. **The budget is literally `«$X»/month` with no number given**, so we define it ourselves (see §4).

## 2. The problem (summary)

Engagement Files are created from versioned Product Templates. When a template update is published, each firm must **apply or decline** it per engagement. Users need:
1. An at-a-glance indicator of which engagements have pending updates.
2. A human-readable summary of the pending changes, to support the apply/decline decision.

Product wants this **in real time, within seconds of a template publish**.

### Hard facts and constraints
- Template DB: shared across firms; stores all versions; fast lookup by (templateId, version); has a fast JSON diff between two versions. Holds no engagement data.
- Engagements: stored in per-customer databases. Each one stores the templateId and version it was created from. **Reading that requires loading the engagement, which takes ~1 min per file (hard constraint).**
- The engagement loader system also handles engagement creation and the apply/decline decision. It is owned by another team with **limited capacity**.
- We may add hooks/events to the template storage system and to the engagement management system.
- Everything is live: no maintenance window. Existing engagements must be covered (migration/backfill).
- Data residency: some firms must stay in-region (EU, Canada). Engagement content is client-confidential. Template content is not firm-specific.
- Version history is **not linear**: markets branch, versions get withdrawn after publish, and declining v5 then being offered v7 (which contains v5's changes) must be handled. Several updates can accumulate before a decision. We must define what "declined" pins.
- Summaries shown in March may need to be **defended in a regulatory review in November**, so they need auditability and reproducibility.
- Out of scope: actually applying template content.

## 3. Scale parameters (configurable, not hard-coded)

These numbers may change, so they live in a config record and are selected through named scenarios.

```java
enum Region { US, EU, CA }

enum BudgetTier { LEAN, TARGET, CAP }

record ScaleProfile(
    int firms,
    int activeEngagements,
    int products,
    int medianEngagementsPerFirm,
    int maxEngagementsPerFirm,
    Duration engagementLoadTime,
    Duration publishCadencePerProduct) {}

enum Scenario {
    BASE(new ScaleProfile(4_000, 800_000, 40, 40, 40_000,
            Duration.ofMinutes(1), Duration.ofDays(7))),
    GROWTH_2X(/* 2x firms and engagements */ null),
    WORST_CASE(/* largest firm on every product, burst publishes */ null);

    final ScaleProfile profile;
    Scenario(ScaleProfile p) { this.profile = p; }
}
```

The design doc's cost tables should show every scenario side by side.

## 4. Budget tiers (our answer to `«$X»`)

| Tier | What it buys |
|---|---|
| **LEAN** | Event-driven fan-out, deterministic (rule-based) summaries only, no LLM |
| **TARGET** | LEAN plus one LLM summary per (product, from→to version) pair, cached and shared across all firms |
| **CAP** | Hard ceiling with cost alarms and a kill-switch that degrades to LEAN |

Key cost insight: template content is not firm-specific, so summaries are generated **once per version pair**, not per engagement. At ~40 products × ~1 update/week, inference spend is small. The dominant costs are downstream engagement loads (~1 min each) and per-region infrastructure. Exact dollar figures come from the arithmetic in the design doc.

## 5. Deliverables

1. **Design doc** (2–4 pages; diagrams don't count) in Markdown, exported to PDF. It covers:
   1. Architecture and data ownership
   2. Correctness and production evolution
   3. Scale, cost and operations, with the arithmetic shown
   4. Generating and evaluating the human-readable summaries
   5. Tradeoffs, assumptions, and the one requirement to challenge
   - Also: migration/backfill, unreliable event delivery, observability/SLOs, data residency, failure recovery, the **riskiest part**, and **what we deliberately left out**.
2. **Java implementation:** the **template-publish fan-out worker**. It must handle concurrency, failure, idempotency and downstream capacity, with a focus on interfaces, contracts and correctness. Tests are a plus. Not a full app.
3. **README** explaining the implementation tradeoffs.
4. (Optional) AI session history.
5. Diagrams.

Time box: about 4 hours. Prefer sharp decisions over hedging.

## 6. Documentation tooling decision

- **Mermaid** for all diagrams. It is free (MIT) and GitHub renders it natively in Markdown.
- **beautiful-mermaid** (`npm i beautiful-mermaid`, MIT) to export polished SVGs for the PDF and ASCII for the README if needed. It is a library with no CLI, so export through a small Node script.
- D2 was rejected because GitHub doesn't render it and its best layout engine (TALA) is paid.

Planned diagrams:
- Architecture / regions (flowchart)
- Publish → fan-out → indicator → decision (sequence)
- Engagement update status lifecycle (state)
- Template version DAG with branch, withdraw and declined-v5-then-v7 cases (flowchart)
- Data ownership (ER)

## 7. Interview follow-up prep (AI usage)

Keep notes during the session on:
- Where AI helped
- Where we corrected or ignored AI output
- How to guide other engineers using AI on this system
- Where AI should not be trusted in this domain, e.g. audit summaries without traceability, or cost arithmetic without verification

## 8. Open decisions (to resolve in the session)

- [ ] Dollar values for LEAN / TARGET / CAP
- [ ] Semantics of "declined": what exactly it pins (version or change-set)
- [ ] Backfill strategy for existing engagements, given the ~1 min loads and limited downstream capacity
- [ ] The one requirement to challenge (likely "within seconds" for the summary, as opposed to the indicator)
- [ ] Repo layout and build tool (Maven or Gradle), and the Java version
