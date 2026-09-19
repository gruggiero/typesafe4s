# Concept Inventory

<!-- PROJECT-SCOPED LIVING DOCUMENT (schema v5) — this template renders to
     openspec/concept-inventory.md, sibling of the behavioural registry
     openspec/concepts/ and of openspec/capability-profile.md.

     IT DOES NOT GO IN A CHANGE DIRECTORY. Per-change copies were retired at
     v5: they died with archiving, destroyed the "Introduced By spec:X"
     provenance this workflow promises, and forced every change to re-scan and
     excavate the archive to reconstruct history. Each change now writes an
     inventory-check.md SNAPSHOT REPORT instead; this file is never archived.

     This is the TYPE-level catalogue. Its behavioural companion is
     openspec/concepts/ — purpose / state / actions / operational principle.

     PURPOSE: prevent duplicate creation of domain concepts. Before creating
     any new type, the apply phase MUST check this inventory and reuse what is
     here.

     CREATING IT: scanner/scan.sh . --output openspec/concept-inventory.md
     Use the SEMANTIC scanner (scala-cli + Scalameta, multi-module). Do not
     hand-write rows and do not regex-scan: the v4 regex scanner reported
     "0 opaque types, 0 sealed types, 0 case classes" on a populated
     multi-module repository and exited 0, and the hand-written inventories it
     replaced recorded constraints and variant sets that the source
     contradicted. A scan that finds nothing on a populated repository is a
     BROKEN SCAN, not an empty project.

     PARSE FAILURES ARE AN INCOMPLETE SCAN, NOT A CLEAN ONE. The scanner
     counts and reports files it cannot parse. Record them here as a DATED
     known gap, and re-establish the gap in a later change rather than
     inheriting it.

     MAINTENANCE RULES:
     - REFRESH it per change; do NOT re-create from scratch — a fresh scan
       overwrites which spec introduced each concept
     - Fix stale rows PRESERVING the provenance column
     - Apply Step 11 appends new concepts with `spec:<change>/<spec>`
       provenance; `scan:<file>` marks a concept predating the registry
     - Package paths must be exact (used for import statements)
     - Constraints must be exact, READ FROM SOURCE (used for Iron
       verification) — record the constraint the code has, not the one the
       spec intended -->

## Opaque Types (Iron Refined)

<!-- Types with compile-time constraints via Iron. These make invalid
     states unrepresentable. Example:
     opaque type AccountId = String :| (MinLength[10] & MaxLength[10]) -->

| Type | Underlying | Iron Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| <!-- AccountId --> | <!-- String --> | <!-- MinLength[10] & MaxLength[10] & Match["^[a-zA-Z0-9]+$"] --> | <!-- domain --> | <!-- spec:accounts --> |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Record ALL variants — the compiler enforces completeness. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| <!-- TransactionError --> | <!-- enum --> | <!-- InsufficientFunds, AccountNotFound, SameAccountTransfer --> | <!-- domain --> | <!-- spec:accounts --> |

## Case Classes (Domain Value Objects)

<!-- Immutable data carriers in domain packages. Record field types
     to enable concept reuse (e.g., reusing Account across specs). -->

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| <!-- Account --> | <!-- id: AccountId, ownerName: String, balance: Balance --> | <!-- domain --> | <!-- spec:accounts --> |

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_].
     Record all methods to enable extension (adding methods to
     existing traits) rather than parallel creation. -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| <!-- AccountService[F[_]] --> | <!-- F: Async --> | <!-- getAccount, deposit, withdraw, transfer --> | <!-- service --> | <!-- spec:accounts --> |

## Smithy Models

<!-- Smithy IDL service and structure definitions that drive
     smithy4s code generation, JSON codecs, and Ring 5 span validators. -->

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| <!-- AccountService --> | <!-- service --> | <!-- Withdraw, Deposit, Transfer --> | <!-- src/main/smithy/account.smithy --> | <!-- spec:accounts --> |

## ScalaCheck Generators

<!-- Reusable generators for property-based tests. Record these to
     avoid duplicate generator creation across specs. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| <!-- genAccountId --> | <!-- Gen[AccountId] --> | <!-- test/.../Generators.scala --> | <!-- spec:accounts --> |

## Cats Effect Resources and Middleware

<!-- Shared resources (connection pools, HTTP clients, caches) and
     middleware (logging, tracing, auth) that specs may depend on. -->

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| <!-- n/a --> | <!-- n/a --> | <!-- n/a --> | <!-- n/a --> | <!-- n/a --> |
