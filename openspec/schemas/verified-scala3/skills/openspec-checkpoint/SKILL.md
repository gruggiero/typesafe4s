---
name: openspec-checkpoint
description: >
  Present a summary of a completed spec's verification results and wait
  for human approval before proceeding to the next spec. This is the
  gate between one spec's implementation and the next in the depth-first
  pipeline.
globs:
  - "openspec/changes/*/implementation-order.md"
  - "openspec/changes/*/implementation-progress.md"
  - "openspec/concept-inventory.md"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Checkpoint Skill

## Purpose

After a spec has been fully implemented and verified through all applicable
rings, this skill presents a structured summary of the results and creates
a human decision point. The human can approve (proceed to next spec),
request changes (re-run affected rings), or abort.

## When to Use

- Automatically invoked as Step 12 of `/opsx:next-spec`
- Manually via `/opsx:checkpoint` to review current state

## Summary Format

Present the following structured summary. Use exact formatting — the human
relies on scanning this quickly to decide approve/reject.

```
═══════════════════════════════════════════════════════════════════
SPEC COMPLETED: <spec-name>
═══════════════════════════════════════════════════════════════════

Ring Results:
  <status> Ring 0: Compilation — <result> (<N> iterations)
  <status> Ring 1: Lint — <result> (<N> iterations)
  <status> Ring 1.5: Architecture — advisory (no rules installed) / <result>
  <status> Ring 2: Property tests — <result> (<N> iterations, <M> properties)
  <status> Ring 8: Adversarial review — <P> PASS / <Q> PARTIAL / <R> FAIL
                                        (fresh-context: yes/no)
  <status> Ring 3: Mutation — <result> (score: <N>%) / skipped <reason>
  <status> Ring 4: Formal — <result> (<N>/<N> VCs valid, <solver>) / skipped <reason>
  <status> Ring 5: Telemetry — <result> (<N> contracts, <M> properties) / skipped

Files created:
  + <path/to/new/file.scala>
  + <path/to/new/test.scala>

Files modified:
  ~ <path/to/existing/file.scala> (<description of change>)

Concepts added to inventory:
  + <ConceptName> (<kind>) in <package>

Concepts extended:
  ~ <ExistingConcept>: +<variant/method>

Progress: <completed>/<total> specs complete
Next: <next-spec-path> (or "All specs complete — ready for archive")

═══════════════════════════════════════════════════════════════════
Approve to continue, request changes, or abort.
═══════════════════════════════════════════════════════════════════
```

### Status Icons

- `✓` — Ring passed
- `✗` — Ring failed (should not appear at checkpoint — failures are handled earlier)
- `○` — Ring skipped (not applicable for this spec)
- `⚠` — Ring passed with warnings

### Iteration Counts

Report the number of LLM fix iterations per ring:
- `(1 iteration)` — passed on first try (ideal)
- `(N iterations)` — required N-1 fixes before passing
- Properties count for Ring 2: how many forAll properties were tested

## Human Response Handling

### Approved

The human says something like "approve", "continue", "next", "looks good", "ok".

Action: The next invocation of `/opsx:next-spec` will pick up the next
unchecked spec in implementation-order.md.

### Changes Requested

The human requests specific changes, e.g.:
- "Add a property for edge case X"
- "The error type should also include variant Y"
- "Move this type to a different package"

Action:
1. Apply the requested changes to the source code
2. Re-run ONLY the affected rings:
   - Source change → re-run from Ring 0
   - New test → re-run from Ring 2
   - Package move → re-run from Ring 1.5
3. Update the concept inventory if the changes affect it
4. Present an UPDATED checkpoint summary
5. STOP again for approval

### Abort

The human says "abort", "stop", "cancel".

Action:
1. Note the current state in implementation-progress.md
   (leave the current spec's checkbox unchecked)
2. Report:
   ```
   Spec implementation paused at: <spec-name>
   Rings completed: <list>
   Files created (kept): <list>

   To resume: /opsx:next-spec (will retry this spec)
   To discard: manually delete created files and re-run /opsx:next-spec
   ```

## Final Checkpoint

When ALL specs in implementation-order.md are checked:

```
═══════════════════════════════════════════════════════════════════
ALL SPECS COMPLETE
═══════════════════════════════════════════════════════════════════

Total specs implemented: <N>
Total ring iterations: <sum across all specs>
Total concepts in inventory: <count>
Total properties verified: <count>

Summary per spec:
  ✓ <spec-1>: R0 R1 R1.5 R2 — <N> iterations
  ✓ <spec-2>: R0 R1 R1.5 R2 R4 — <N> iterations
  ✓ <spec-3>: R0 R1 R2 R5 — <N> iterations

Ready for /opsx:archive to merge delta specs and complete the change.
═══════════════════════════════════════════════════════════════════
```


## Schema v5 additions

**Ring numbering is this project's**: 0 compile · 1 lint · 1.5 architecture ·
2 property tests · 3 mutation · 4 formal · 5 telemetry · **8 adversarial
review**. Ring 8 runs BEFORE 3/4/5 and is reported above them.

**Three statuses, not two.** A ring is:
- `✅` — it ran and could have failed the build
- `advisory` — its tooling is not installed, so the check was a human reading
  (Ring 1.5 in this project: `.scalafix.conf` has no architecture rules)
- `⏭️` — not applicable to this spec, or unavailable in this project
  (Ring 4 IS available here — `verified/` — so it is `⏭️` only when this spec
  has no invariant worth mirroring, and the reason is stated)

`advisory` and `⏭️` are NOT `✅`. A ring reported as passing when no rule ran is
a claim outrunning its evidence, and it is the single most common way this
workflow has been wrong in the past.

**Evidence is pasted, not described.** Every `✅` carries the actual final lines
of the tool output — the success line and test counts. A ring claimed ✅ with no
pasted output is not verified and the checkpoint is invalid. Never present a
checkpoint for code that has not been compiled.

**Additional required fields (schema v5):**

```
**Commit:** <sha> (baseline was <baseline-sha>)
**Gate tier:** two gates | combined | waiver
**Oracle polarity:** <N red→green confirmed, M green-by-design held>
**Adversarial review (Ring 8):** <verdict per requirement> (fresh-context: yes/no)
**Impact scan:** <sites found + resolution | n/a> (method: metals | grep)
**Proof obligations:** <all enforced | manually-reviewed ones listed>
**Concept delta (scanner diff):** <exactly as committed | deviations + resolution>
**Build-dependency delta:** <none | new deps + where approved>
**Removal audit:** <orphans + resolution | none>
```

**A checkpoint may not be presented as complete while any Ring 8 requirement is
FAIL.** A PARTIAL is presented explicitly for the human's decision — it is
never rounded up to a pass.
