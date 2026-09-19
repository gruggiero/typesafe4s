---
name: openspec-update-inventory
description: >
  After implementing a spec through the verification rings, append any new
  domain concepts to openspec/concept-inventory.md (PROJECT-scoped). This maintains the
  living catalogue that prevents duplicate type creation in subsequent specs.
globs:
  - "openspec/concept-inventory.md"
  - "src/main/scala/**/*.scala"
  - "src/test/scala/**/*.scala"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Update Inventory Skill

## Purpose

After a spec has been implemented and verified through all applicable rings,
this skill appends any NEW concepts to the concept-inventory.md document.
It is Step 9 in the depth-first apply pipeline.

## When to Use

- After every spec's Ring pipeline completes during `/opsx:apply`
- After manual code changes that introduce new domain types
- When the human requests an inventory refresh

## Procedure

### 1. Identify the Completed Spec

Read `openspec/changes/<CHANGE_NAME>/implementation-order.md` to find which
spec was just implemented. Read that spec's "Concepts Introduced" table to
know what NEW concepts should now exist in the source code.

### 2. Verify Concepts Were Actually Created

For each concept listed in the spec's "Concepts Introduced" table, verify
it exists in the source code:

```bash
# For opaque types:
grep -r "opaque type <ConceptName>" src/main/scala/

# For enums/sealed traits:
grep -r "enum <ConceptName>\|sealed trait <ConceptName>" src/main/scala/

# For case classes:
grep -r "case class <ConceptName>" src/main/scala/

# For service trait methods:
grep -r "def <MethodName>" src/main/scala/

# For generators:
grep -r "val gen<n>\|Gen\[<Type>\]" src/test/scala/
```

If a declared concept was NOT created, flag it as a discrepancy:
```
⚠ Spec declared concept '<n>' but it was not found in source code.
  Check if the implementation deviated from the spec.
```

### 3. Append to Inventory

Open `openspec/concept-inventory.md` — the PROJECT-scoped living
inventory (schema v5). It is NOT in the change directory: per-change
copies died with archiving and destroyed the provenance column.

For EACH verified new concept, append a row to the appropriate table section.
Use the spec name as the "Introduced By" value.

**CRITICAL RULES:**
- APPEND ONLY — never modify or remove existing rows
- Use the spec name in the "Introduced By" column, e.g., `spec:transfers`
- Record the ACTUAL package path from the source code, not the spec's planned path
- Record the ACTUAL constraint as implemented, not the spec's planned one. This
  project uses plain Scala 3 opaque types, so the constraint is the smart
  constructor / validation function or an `inline`+`compiletime.error` check —
  there are no Iron refinements to record.
- If an EXISTING concept was EXTENDED (new variant added to an enum, new method
  added to a trait), do NOT add a new row. Instead, MODIFY the existing row to
  include the new variants/methods and add a note: `(extended by spec:transfers)`

### 4. Handle Extensions

When a spec extends an existing concept rather than creating a new one:

**Enum extension** — A new variant was added to an existing enum:
- Find the existing enum row in the Sealed Traits table
- Update the "Variants" column to include the new variant
- Append to the "Introduced By" column: `(+Variant by spec:name)`

**Service trait extension** — A new method was added to an existing trait:
- Find the existing trait row in the Service Traits table
- Update the "Methods" column to include the new method
- Append to the "Introduced By" column: `(+method by spec:name)`

**New generator for existing type** — A new Gen/Arbitrary for an existing type:
- Simply append a new row to the Generators table

### 5. Report Changes

After updating, report:
```
Inventory updated for spec:<spec-name>:
  Added:
  - <ConceptName> (<kind>) to <section>
  Extended:
  - <ExistingConcept>: +<variant/method> (by spec:<spec-name>)
  Unchanged: N existing concepts
```

## Example

After implementing `specs/transfers/spec.md` which declared:

```markdown
## Concepts Introduced (new)
| Concept | Kind | Description |
|---------|------|-------------|
| TransferResult | case class | Holds from/to balances after transfer |
```

The update adds one row to the Case Classes table:

```markdown
| TransferResult | fromBalance: Balance, toBalance: Balance | domain | spec:transfers |
```

And if `TransactionError` gained a new variant `TransferLimitExceeded`:

The existing row changes from:
```
| TransactionError | enum | InsufficientFunds, AccountNotFound, SameAccountTransfer | domain | scan:Types.scala |
```
to:
```
| TransactionError | enum | InsufficientFunds, AccountNotFound, SameAccountTransfer, TransferLimitExceeded | domain | scan:Types.scala (+TransferLimitExceeded by spec:transfers) |
```

## Important Rules

- NEVER delete existing inventory entries
- ALWAYS verify concepts exist in source code before adding to inventory
- Use ACTUAL source code details, not planned/spec details
- Extensions modify existing rows; new concepts add new rows
- The "Introduced By" column is the audit trail — always populate it


## Schema v5 rules

- **Path**: `openspec/concept-inventory.md`, never a per-change copy.
- **Provenance**: append new rows with `spec:<change>/<spec>` in the
  "Introduced By" column, so the next change inherits the history instead of
  re-scanning and excavating the archive. `scan:<file>` marks a concept that
  predates the registry.
- **Read the constraint from SOURCE, not from the spec.** The v4
  hand-maintained inventories recorded `OfferingCode` as `Not[Empty]` when the
  source says `OfferingCodeConstraint`, and gave `PastCondition` 3 variants
  when the code has 11. Record what the code has, not what was intended.
- **Extensions modify the existing row**; they do not add a parallel one. A new
  enum variant edits that enum's Variants cell; a new trait method edits that
  trait's Methods cell.
- **Do NOT re-run the scanner over the whole file to "refresh" it** — a fresh
  scan overwrites the provenance column. Use the scanner's before/after
  snapshots for the Step 11 delta, and hand-append the verified new rows here.
