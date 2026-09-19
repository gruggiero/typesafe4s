# Concept Registry

Behavioral concepts for **typesafe4s**, in the sense of Meng & Jackson,
*"What You See Is What It Does: A Structural Pattern for Legible Software"* (arXiv:2508.14511): a **concept** is an
independent unit of user-facing functionality with a purpose, its own state, a set of actions, and an operational
principle. Concepts never reference each other; cross-concept behaviour is expressed as **synchronizations** —
declarative `when / where / then` rules.

## Status: design-time registry

This registry is normally **descriptive** — it names concepts already latent in the code. typesafe4s adopted it
**before** the code existed, so read it with that caveat:

- The **concept specifications** are real and sourced. They describe behaviour the TypeSafe System One API already
  has, taken from <https://docs.typesafe.ai> and from this repository's specs — not invented.
- The **Implementation maps are deliberately empty.** Filling one in is part of implementing the spec that realizes
  it, exactly as the living-document rule requires. `scanner/registry-check.sh` verifies every backticked symbol in
  an Implementation map against the source tree, so an empty map is honest and a speculative one would fail the
  build. **Do not add a symbol to a map before the code exists.**

## Relation to `concept-inventory.md`

`openspec/concept-inventory.md` catalogs **types** — "does this type already exist?". This registry catalogs
**behaviour** — "what is the stable name for the thing this spec changes?". Specs refer to behaviour as
`Concept/action`; code identifiers live in exactly one place per concept, the Implementation map, so a refactor
updates one table rather than every spec that mentioned a type.

## What is deliberately NOT a concept here

**Effect portability.** The project's defining constraint — one shared runtime over the `kyo-compat` carrier,
cross-compiled into five backend artifacts — is a *realization strategy*, not a unit of user-facing functionality.
It has no state and no actions of its own; it changes the type every action returns. It is specified in
`docs/architecture-analysis.md` §2 and the `effect-portability` spec, and verified by Ring 5 (cross-backend parity).
Recording it here would give it a shape the pattern does not fit.

## Rules for spec authors

- **Behavioural vocabulary only** in requirements and scenarios: `Concept/action`, API surface language, domain
  terms, concrete test vectors. Module names, error class names and build commands belong in the spec's
  `## Implementation Anchors`.
- **Negative requirements need adversarial scenarios.** Any requirement containing "only", "never" or "must not"
  gets at least one scenario whose input the requirement forbids.
- **MUST-CONFIRM for external data.** Value domains whose source of truth is outside this repo are marked in the
  spec, and their provenance is recorded in the concept's Value domains table. Several of ours are currently
  unconfirmed — see `evaluation.md` and `retry.md`.
- **Living document.** Altering a concept's state, actions or syncs is part of the change that does it.

## Machine check

```bash
bash openspec/schemas/verified-scala3/scanner/registry-check.sh .
```

Three passes: backticked symbols in Implementation maps still exist; declared fold fields are actually populated;
every `Concept/action` cited by an active change spec is declared here. Wire it into CI — it is dependency-free
(bash + git grep).

## Catalog

| Concept | Status | One-line purpose |
|---|---|---|
| [Evaluation](evaluation.md) | 📐 specified | Obtain typed judgments about one state in a single exchange |
| [Noul](noul.md) | 📐 specified | Report the probability that a yes/no claim about the state holds |
| [Choice](choice.md) | 📐 specified | Select one option from a closed set, with the distribution over all of them |
| [Score](score.md) | 📐 specified | Place the state on an ordered rubric of described levels |
| [Confidence](confidence.md) | 📐 specified | Turn a distribution's shape into one number a caller can gate on |
| [TokenBudget](token-budget.md) | 📐 specified | Keep an exchange inside the model's shared budget, splitting when it does not fit |
| [Retry](retry.md) | 📐 specified | Survive transient failure within a bounded budget |
| [Credential](credential.md) | 📐 specified | Authenticate an exchange without ever revealing the secret |

📐 specified = behaviour defined, Implementation map empty. ✅ = implemented and mapped.

## Synchronization index

| Sync | Trigger | Effect | Defined in |
|---|---|---|---|
| ConfidenceFromChoice | Choice/answer | Confidence/derive | [confidence.md](confidence.md) |
| ConfidenceFromScore | Score/answer | Confidence/derive | [confidence.md](confidence.md) |
| BudgetedEvaluation | Evaluation/ask | TokenBudget/fit | [token-budget.md](token-budget.md) |
| SplitOversizedEvaluation | TokenBudget/fit rejects | TokenBudget/split → Evaluation/ask per batch | [token-budget.md](token-budget.md) |
| RetryTransientExchange | Evaluation/ask fails transiently | Retry/schedule → Evaluation/ask | [retry.md](retry.md) |
| AuthenticateExchange | Evaluation/ask | Credential/present | [credential.md](credential.md) |
