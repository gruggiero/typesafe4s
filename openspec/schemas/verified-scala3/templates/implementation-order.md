# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs and design artifacts.
     The checkbox list at the bottom is the progress tracker used by
     the apply phase (tracks: implementation-progress.md). -->

## Current position

<!-- Maintained at every spec boundary (next-spec Step 12). A new session
     reads THIS block plus state.md and resumes — no scan of the checkbox
     list needed. The checkbox list stays authoritative; this block is its
     one-glance cache, and state.md (same directory) is the durable ledger:
     baseline SHA, gate/ring status with evidence, capability commands,
     known scanner gaps, next action. If this block and the checkboxes
     disagree, the checkboxes win — fix the block. -->

- Next spec: <!-- n/m `specs/<name>/spec.md`, or "done — all specs" -->
- Last commit: <!-- sha of the previous spec's Step-12 commit, or "none" -->
- State ledger: `state.md` <!-- rewritten at every step boundary -->

## Dependency Analysis

<!-- For each spec, list what it introduces and what it consumes.
     This determines the topological sort order. -->

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| <!-- 1 --> | <!-- specs/accounts/spec.md --> | <!-- AccountId, Account, Balance, Amount, AccountService --> | <!-- (none — foundational) --> | <!-- high --> |
| <!-- 2 --> | <!-- specs/transfers/spec.md --> | <!-- TransferResult --> | <!-- AccountId, Account, Balance, Amount --> | <!-- medium --> |
| <!-- 3 --> | <!-- specs/notifications/spec.md --> | <!-- (none — uses existing) --> | <!-- AccountId, TransferResult --> | <!-- simple --> |

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections. -->

| # | Spec | R0 | R1 | R1.5 | R2 | R3 | R4 | R5 | R8 | Gate tier |
|---|------|----|----|------|----|----|----|----|----|-----------|
| <!-- 1 --> | <!-- accounts --> | ✅ | advisory | advisory | ✅ | <!-- ✅ --> | <!-- ⏭️ --> | <!-- ✅ --> | ✅ | <!-- two gates --> |
| <!-- 2 --> | <!-- transfers --> | ✅ | ✅ | advisory | ✅ | <!-- ✅ --> | <!-- ⏭️ --> | <!-- ⏭️ --> | ✅ | <!-- two gates --> |
| <!-- 3 --> | <!-- notifications --> | ✅ | ✅ | advisory | ✅ | <!-- ⏭️ --> | <!-- ⏭️ --> | <!-- ✅ --> | ✅ | <!-- combined --> |

<!-- RING NUMBERING IS THIS PROJECT'S: 0 compile · 1 lint · 1.5 architecture ·
     2 property tests · 3 mutation · 4 formal · 5 telemetry · 8 adversarial
     review. Do NOT renumber to match adk4s — ~100 archived specs, the `ring3`
     command alias, openspec/ring5-findings/ and the observability/ring5/
     package all encode this numbering.

     A ring's cell is a FACT read from openspec/capability-profile.md, not an
     aspiration. Use:
       ✅        the ring runs and can fail the build
       advisory  the ring's tooling is not installed; the check is a human
                 reading (Ring 1.5 has no architecture rules in this project)
       ⏭️        not applicable to this spec, or unavailable in this project
                 (Ring 4 IS available here — the `verified/` Stainless build —
                 so ⏭️ means this spec has no invariant worth mirroring)
     Ring 2 is ✅ for every code-changing spec. Ring 8 is ✅ for every
     code-changing spec. Neither is a judgment call. -->

## Complexity Guide

<!-- Complexity determines the GATE TIER and review depth.

     SIMPLE: No new types, <=1 new method on an existing trait, no new error
             variants. Gate tier: COMBINED. Rings 0, 1, 2, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Gate tier: TWO GATES. Rings 0, 1, 1.5, 2, 3, 8.

     HIGH:   New types AND complex logic AND involves Ring 4 or Ring 5.
             Gate tier: TWO GATES. All applicable rings. -->

## Gate Tiers

<!-- Replaces v4's "Pseudocode?" column. v4 wrote pseudocode to
     openspec/changes/<c>/pseudocode/*.scala and ran `sbt compile` — a path
     sbt never compiles. The gate was backed by nothing. In v5 the artifact is
     a TYPED CONTRACT that lives in the owning module's TEST sources and is
     compiled with `sbt <module>/Test/compile`.

     TWO GATES (default for medium/high)
       Step 1 stops for the typed contract; Step 2 stops again for the test
       oracle. Use when the spec introduces new domain types, a new error
       algebra, or complex business logic — approving a wrong type surface
       means rewriting the oracle written against it.

     COMBINED (simple specs)
       Step 1 does NOT stop. The typed contract is presented TOGETHER with the
       test oracle at a single gate at the end of Step 2. The contract is
       still WRITTEN and still COMPILED — combined means one interruption,
       never one less artifact.

     WAIVER
       No gate. Requires explicit human approval recorded in the table below,
       with a reason. NOT available for any spec that introduces a concept.

     Ceremony proportional to risk is the point: two gates on a one-method
     change is how a workflow teaches people to route around it. -->

| # | Spec | Tier | Reason |
|---|------|------|--------|
| <!-- 1 --> | <!-- accounts --> | <!-- two gates --> | <!-- introduces 4 domain types + error algebra --> |
| <!-- 3 --> | <!-- notifications --> | <!-- combined --> | <!-- one method on an existing trait, existing types --> |

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     0. Record the BASELINE SHA (git rev-parse HEAD) + inventory snapshot
     1. Read openspec/concept-inventory.md — import existing concepts
     2. Write the TYPED CONTRACT in test sources; compile it  [GATE 1]
     3. Write the TEST ORACLE from the spec; run it for polarity  [GATE 2]
     4. Implement, then run rings 0 → 1 → 1.5 → 2
     5. RING 8 adversarial review in a FRESH-CONTEXT subagent
     6. Rings 3 → 4 → 5 as applicable
     7. Concept delta check; update openspec/concept-inventory.md
     8. Commit, then STOP for human validation before the next spec

     The BASELINE is load-bearing: every diff a spec's rings compute (Ring 8
     review, Ring 3 mutation targeting, Step 11 deltas) is taken against it,
     never against the unanchored working tree — which after spec 1 contains
     other specs' changes.

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

| # | Spec | Baseline SHA | Commit |
|---|------|--------------|--------|
| <!-- 1 --> | <!-- accounts --> | <!-- recorded at Step 0 --> | <!-- recorded at Step 12 --> |

- [ ] 1. `specs/[first-spec]/spec.md` — [brief description]
- [ ] 2. `specs/[second-spec]/spec.md` — [brief description]
- [ ] 3. `specs/[third-spec]/spec.md` — [brief description]

<!-- Add more entries as needed. The checkbox list length must match
     the number of spec files. -->
