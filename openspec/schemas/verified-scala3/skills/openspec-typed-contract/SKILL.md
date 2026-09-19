---
name: openspec-typed-contract
description: >
  Generate a Scala 3 TYPED CONTRACT for a spec before implementation: type
  declarations, public method signatures with ??? bodies, the error algebra,
  smart constructors, and assertDoesNotCompile stubs for compile-negative
  obligations. The contract lives in the owning module's TEST sources so that
  `sbt <module>/Test/compile` gives genuine type-level evidence, and is the
  human-reviewable commitment approved at Gate 1 before any implementation or
  test oracle is written. Replaces the v4 openspec-pseudocode skill.
globs:
  - "openspec/changes/*/specs/**/*.md"
  - "openspec/concept-inventory.md"
  - "openspec/capability-profile.md"
  - "**/src/test/scala/**/typecontract/*.scala"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Typed Contract Skill (apply Step 1)

## Purpose

Capture the TYPE-LEVEL structure of a spec — with no implementation logic — as
a file the compiler actually checks. Three goals:

1. **Genuine early type checking.** The contract compiles against the real
   project classpath, catching structural errors before any logic exists.
2. **A small human review surface.** Types, signatures and the error algebra
   are fast to review compared to a full implementation.
3. **A commitment.** Step 3 must implement the signatures as approved; Step 2's
   test oracle compiles against them.

## What changed from v4 (`openspec-pseudocode`)

v4 wrote `openspec/changes/<CHANGE>/pseudocode/<spec>.scala` and then ran
`sbt compile`.

**`openspec/changes/` is not in the sbt source graph.** That compile never
touched the file. Every "pseudocode compiles ✅" claim in a v4 checkpoint was
evidence of nothing, and the human approved a skeleton that had never been
type-checked. Under `-Werror` with exhaustiveness escalation, a real compile
here is worth a great deal — so v5 moves the file where the compiler will see
it, and renames it to what it actually is.

## When to use

- **apply Step 1**, for every spec. There is no "pseudocode not recommended"
  branch any more: the contract is always written and always compiled. What the
  spec's **Gate tier** controls is only whether Step 1 STOPS for its own
  approval (`two gates`) or is presented together with the oracle at a single
  gate (`combined`).
- Manually, to preview a type surface before committing to it.

## Procedure

### 1. Read the inputs

- `openspec/changes/<CHANGE>/specs/<spec-name>/spec.md`
  - **Concepts Used (behavioral)** / **Implementation Anchors** → imports
  - **Concepts Introduced (new)** → new type declarations (a COMMITMENT:
    Step 11 machine-diffs the implementation against this table)
  - **Properties (Ring 2)** → property obligations as structured comments
  - **Compile-Negative Obligations** → `assertDoesNotCompile` stubs
  - **Type-Widening Impact** → note which public types grow
  - **Temporal Properties (Ring 5)** / **Formal Contracts (Ring 4)** → sketches
- `openspec/concept-inventory.md` (PROJECT-scoped, not in the change dir) →
  the EXACT package path for every import, and the existing methods of any
  service trait being extended.
  **Look these up; do not read the file whole** — it is ~940 rows and grows.
  One pass over the names the spec gives you:
  ```bash
  grep -nE '^\| `?(NameA|NameB|NameC)`? \|' openspec/concept-inventory.md
  ```
  A row carries the package, the constraint (smart constructor or compile-time
  check — this project has no Iron) and the variant list — the
  three things a contract must get exactly right. A name with no row is a
  concept the spec assumes and the project does not have: STOP, do not invent
  a package path for it.
- `openspec/capability-profile.md` → the owning module, the contract placement
  rule, and the exact `Test/compile` command

### 2. Choose the location

From the capability profile's **Typed Contract Placement** section:

```
<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala
```

The owning module is whichever will hold the implementation. Do **not** put the
contract in `openspec/changes/...`, and do not invent a new module for it.

### 3. Generate the contract

Include, and nothing else:

- `import` statements for every reused concept, with exact packages
- New `opaque type` / `enum` / `case class` / `sealed trait` declarations from
  Concepts Introduced, with their constraints
- Public method and service/actor command signatures with `???` bodies
- The error algebra — sealed error types, and new variants on existing ones
- Smart constructors for constrained types (signature only)
- `assertDoesNotCompile("...")` stubs, one per Compile-Negative Obligation
- Property and generator obligations as structured comments, citing the spec
- Formal-contract and temporal-monitor sketches as comments where relevant

Do **not** include implementation logic. `???` is permitted here:
`Wart.TripleQuestionMark` is deliberately excluded from this project's active
wart set.

Every declaration carries a provenance comment:

```scala
// spec: <spec-name> — Concepts Introduced: <concept name>
```

### 4. Compile it

```bash
sbt <module>/Test/compile
```

Fix until it **genuinely compiles**. Paste the success line — that line is the
evidence the gate rests on.

Remember what this build enforces: `-Werror` is active, and
`PatternMatchExhaustivity` / `MatchCaseUnreachable` are escalated to errors. A
contract that compiles here has already proved more than a sketch could.

### 5. Present for Gate 1

Read the spec's **Gate tier** from `implementation-order.md`.

**`two gates`** — present now and STOP:

```
Typed contract for spec: <spec-name>
Location: <module>/src/test/scala/.../typecontract/<Name>TypeContract.scala
Compiled: <pasted sbt success line>

New types:            <list>
Extended types:       <list — which existing file each variant/method lands in>
New method signatures:<list>
Error algebra:        <sealed type + variants>
Compile-negatives:    <count> assertDoesNotCompile stubs
Property obligations: <count>

[contract content]

Review this type surface. Once approved it is a commitment: the test oracle
(Step 2) compiles against these signatures and the implementation (Step 3)
must keep them as approved.
```

**Wait for explicit human approval.**

**`combined`** — do not stop. Carry this presentation into the single gate at
the end of Step 2, alongside the test oracle. The contract is still written and
still compiled either way.

## Rules

- **Signatures approved at this gate are binding.** If Step 3 needs to change
  one, that is a re-approval, not a quiet edit.
- **Do not weaken a type to make something compile.** Widening a parameter,
  dropping a constraint, or replacing a sealed type with a String to get
  past an error is the defect this workflow exists to catch. If the spec's type
  surface cannot compile, the SPEC needs fixing — raise it.
- **Do not add a concept that is not in Concepts Introduced.** Step 11 diffs
  the scanner's before/after snapshots against that table; anything extra is
  scope creep and must be approved or removed.
- **Never place the contract under `openspec/`.** See "What changed from v4".

## Lifecycle

The contract stays in test sources as a living compile-time assertion of the
spec's type surface. It is an input to Ring 8: the fresh-context reviewer reads
the spec, the contract, and the diff — nothing else. Do not delete it after
implementation.
