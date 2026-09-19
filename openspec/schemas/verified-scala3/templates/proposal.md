# Proposal: [Change Title]

## Problem Statement

<!-- Why is this change needed? What problem does it solve?
     Be specific about the business or technical motivation. -->

## Scope

<!-- What capabilities are affected? What is explicitly OUT of scope? -->

### Affected Capabilities

<!-- List the spec files that will be created or modified.
     Use the format: specs/<capability-name>/spec.md -->

- `specs/<capability-1>/spec.md` — [brief description]
- `specs/<capability-2>/spec.md` — [brief description]

### Out of Scope

<!-- Explicitly state what this change does NOT include. -->

## Approach

<!-- High-level approach to solving the problem. Not the full design
     (that comes in design.md) — just enough to evaluate feasibility. -->

## Verification Strategy

<!-- Check which verification rings apply to this change.
     Ring 0 and Ring 1 always apply. Check others based on the nature
     of the change. This drives the per-spec ring pipeline during apply. -->

- [x] Ring 0: Compilation — strict scalac flags, Iron refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover
- [ ] Ring 1.5: Architecture — layer dependencies, sealed domain types, effect discipline
- [ ] Ring 2: Property-based tests — ScalaCheck invariants
- [ ] Ring 3: Mutation testing — Stryker4s, threshold ____%
- [ ] Ring 4: Formal verification — Stainless, PureScala modules only
- [ ] Ring 5: Telemetry — otel4s span contracts, TraceGate suites

<!-- Ring 5 applies when the change (a) crosses a boundary where refined
     types cannot reach — external client, Kafka, Databricks, runtime-
     interpreted rules — or (b) asserts an ordering between operations.
     Ring 5 does NOT apply merely because an API operation exists. -->

### Pseudocode Phase

<!-- Should the optional pseudocode step be used for this change?
     Recommend YES for changes that introduce new domain types or complex
     business logic. Recommend NO for changes that only add methods to
     existing traits using existing types. -->

- [ ] Enable pseudocode for this change

**Justification**: <!-- Why pseudocode is or isn't needed -->

## Concepts (behavioral)

<!-- If the project has a concept registry (openspec/concepts/), list the
     behavioral concepts this change touches, what changes for each, and
     which registered synchronizations are involved. Concepts whose files
     must be updated as part of the change are flagged here.
     If there is no registry, delete this section. -->

| Concept | What changes | File |
|---------|--------------|------|
| <!-- e.g. Anagrafica --> | <!-- e.g. state gains field X --> | <!-- link --> |

## Existing Concepts to Reuse

<!-- Reference entries from concept-inventory.md that this change will use.
     Keep this to the genuinely load-bearing types — the ones the change
     modifies or whose contracts it depends on — not a full restatement of
     the inventory. If the inventory is empty (new project), write
     "None — new project." -->

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| <!-- e.g. AccountId --> | <!-- e.g. opaque type --> | <!-- e.g. domain --> | <!-- e.g. reuse as-is --> |

## New Concepts to Introduce

<!-- Preview of domain types, service methods, error variants, or Smithy
     operations that this change will create. These are refined in the
     spec and design phases — this is a directional preview. -->

| Concept | Kind | Purpose |
|---------|------|---------|
| <!-- e.g. TransferResult --> | <!-- e.g. case class --> | <!-- e.g. holds from/to balances --> |

## Risks and Mitigations

<!-- What could go wrong? How will you detect and handle it? -->
