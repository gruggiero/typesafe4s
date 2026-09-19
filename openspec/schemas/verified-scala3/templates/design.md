# Design: [Change Title]

## Package Structure

<!-- Define the package layout and layer dependency rules.
     These rules are enforced by the LayerDependencies Scalafix rule (Ring 1.5). -->

### Layers

| Layer | Package | Depends On | Ring 1.5 Rule |
|-------|---------|-----------|---------------|
| Domain | `[base].domain` | Nothing (self-contained) | No outbound imports |
| Service | `[base].service` | Domain only | `allowed: { from: service, to: [domain] }` |
| Infrastructure | `[base].infra` | Domain, Service | `allowed: { from: infra, to: [domain, service] }` |

### New Packages

<!-- List any new packages this change introduces, with their layer assignment. -->

| Package | Layer | Purpose |
|---------|-------|---------|
| <!-- e.g. domain.transfer --> | <!-- Domain --> | <!-- Transfer-related value types --> |

## Effect Boundaries

<!-- Clearly separate pure code (formal verification candidates) from
     effectful code (wrapped in F[_]/IO). This drives Ring 4 applicability. -->

### Pure Code (Ring 4 candidates)

<!-- Functions with no side effects, no I/O, no mutable state.
     These can be verified by Stainless if annotated with contracts. -->

| Module / Function | Purpose | Ring 4? |
|-------------------|---------|---------|
| <!-- e.g. domain.InterestCalculator.compute --> | <!-- Interest computation --> | <!-- Yes --> |

### Effectful Code (F[_] wrapped)

<!-- Service methods, I/O operations, database access, HTTP calls.
     These are tested by Ring 2 (ScalaCheck) and monitored by Ring 5. -->

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| <!-- e.g. service.AccountService[F[_]] --> | <!-- F: Async --> | <!-- Account operations --> |

## Iron Type Strategy

<!-- Which domain values get opaque types with Iron constraints vs. plain types.

     RULE OF THUMB:
     - API boundary values → opaque type with Iron constraint
     - Database-stored identifiers → opaque type with Iron constraint
     - Internal computation intermediates → plain type is acceptable
     - Human-readable strings (names, descriptions) → String is acceptable -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| <!-- e.g. TransactionId --> | <!-- String --> | <!-- UUID format --> | <!-- API + DB identifier --> |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| <!-- e.g. ownerName: String --> | <!-- Human-readable, no structural constraint --> |

## Smithy Model Layout

<!-- Smithy IDL structure for API operations. This drives:
     - smithy4s type generation (Ring 0)
     - JSON codec generation (Ring 1)
     - Span validator generation (Ring 5) -->

### Services

| Service | Operations | Smithy File |
|---------|-----------|-------------|
| <!-- e.g. AccountService --> | <!-- Withdraw, Deposit, GetBalance --> | <!-- src/main/smithy/account.smithy --> |

### Structures

| Structure | Fields | Used By |
|-----------|--------|---------|
| <!-- e.g. WithdrawInput --> | <!-- accountId: AccountId, amount: Amount --> | <!-- AccountService.Withdraw --> |

## Error Strategy

<!-- How errors are modeled, propagated, and exposed. -->

### Error Modeling

<!-- Each service gets a sealed enum for its error types.
     Error variants are data-carrying (not empty marker cases). -->

| Error Enum | Variants | Used By |
|------------|----------|---------|
| <!-- TransactionError --> | <!-- InsufficientFunds(available, requested), AccountNotFound(id) --> | <!-- AccountService --> |

### Error Propagation

<!-- Pure code: Either[E, A]. Effectful code: F[Either[E, A]] or IO.raiseError.
     API boundary: map to HTTP status codes via smithy4s error mapping. -->

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure | `Either[E, A]` | `validateAmount(a): Either[ValidationError, Amount]` |
| Pure → Effect | `IO.fromEither(result)` | Lift pure validation into IO |
| Service → API | `F[Either[ServiceError, Result]]` | http4s maps via smithy4s |

## Verification Map

<!-- For each module, state which rings apply. This feeds directly into
     implementation-order.md and the per-spec ring pipeline. -->

| Module | Ring 0 | Ring 1 | Ring 1.5 | Ring 2 | Ring 3 | Ring 4 | Ring 5 |
|--------|--------|--------|----------|--------|--------|--------|--------|
| <!-- domain types --> | ✅ | ✅ | ✅ | — | — | — | — |
| <!-- service logic --> | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| <!-- pure algorithms --> | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |

## Technical Decisions

### Decision: [Title]

**Context**: [What situation prompted this decision]
**Options considered**: [Brief list of alternatives]
**Decision**: [What was chosen and why]
**Consequences**: [What follows from this decision]

<!-- Add more decision records as needed.
     Use the ADR (Architecture Decision Record) format. -->
