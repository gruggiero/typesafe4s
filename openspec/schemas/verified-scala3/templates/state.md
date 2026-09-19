# State — <change-name>

<!-- Carry-forward ledger for the depth-first implementation workflow.
     One file per change, REWRITTEN at every step boundary (gate, ring,
     paused step) — never appended. A fresh session or post-compaction
     context reads this FIRST; it replaces re-deriving position from
     implementation-order.md / implementation-progress.md / a generated
     summary. Keep it under ~60 lines: ledger, not narrative.

     Evidence rule: a ring verdict is recorded WITH its evidence line
     (test counts, VC counts, mutation score). A verdict without evidence
     is a claim — a resumed session must re-obtain it. -->

spec: <n>/<m> <spec-name>          # or "done — all specs implemented"
step: <current step>               # e.g. "0b concept check", "awaiting Gate 2"
baseline: <sha>                    # this spec's Step-0 baseline; cleared at Step 12
prior-commit: <sha>                # previous spec's Step-12 commit
gate-tier: two gates | combined | waiver
gates: gate1 pending|approved · gate2 pending|approved
rings: R0 pending · R1 pending · R1.5 pending · R2 pending · R8 pending ·
       R3 pending · R4 pending · R5 pending
       # per ring: pending | ✅ <one-line evidence> | advisory | ⏭️ <reason> | ❌
registry: ok                       # or: excluded: <names> (--except-change/--only-change)
metals: down — grep fallback       # or: up <url> (verified) — metals-call.sh
                                   # verifies serverInfo.name == "<repo>-metals";
                                   # "up" means endpoint verified, modules may
                                   # still be indexing (get-usages needs compile)
scanner-gaps: none                 # concepts the inventory misses, if any
deviations: none                   # approved deviations, open findings
open-questions: none               # items flagged for the human gate —
                                   # pending | resolved:<disposition>.
                                   # Unresolved ones carry forward; they
                                   # must not evaporate at the checkpoint.
next: <the exact next action>

## Capability commands

<!-- Copied from openspec/capability-profile.md ONCE at Step 0. Later steps
     use this copy; re-read the profile only if a recorded command fails. -->

compile: …
test: …
lint: …
format: …
mutation: …
formal: …
test-framework: …
test-kits: …
