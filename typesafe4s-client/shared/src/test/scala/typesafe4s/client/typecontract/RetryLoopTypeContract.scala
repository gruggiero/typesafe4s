package typesafe4s.client.typecontract

import kyo.compat.*

import typesafe4s.RetryPolicy
import typesafe4s.client.internal.{Clock, Retry}

// ============================================================================
// TYPED CONTRACT — spec: retry-policy (change: add-typesafe4s-sdk, spec 4/8)
//
// Gate 1 artifact, approved 2026-09-18. The declaration was promoted to
// typesafe4s-client/shared/src/main/scala/typesafe4s/client/internal/
// Retry.scala at Step 3; this file remains as a living compile-time
// assertion of the approved surface — if the implementation drifts from it,
// this file stops compiling.
//
// Approved surface (Gate 1):
//   private[typesafe4s] object Retry
//   Retry.run[A](policy: RetryPolicy, clock: Clock)(operation: CIO[A]): CIO[A]
//
// Approved design decisions (loop side):
//   L1  ONE loop, written once against the carrier — names no backend.
//       `private[typesafe4s]`: the public surface that selects a per-call
//       policy belongs to the client (effect-portability spec); this is the
//       mechanism underneath.
//   L2  The loop is a function of (policy, clock, operation): the policy is
//       an ARGUMENT, so a call cannot mutate any client's own policy.
//   L3  Time and waiting go through the `Clock` seam, never `CIO.sleep` /
//       `CIO.nowMonotonic` directly.
//   L4  Recovery reads the closed family only: a `TypesafeException` is
//       classified by the policy; anything else — including interruption —
//       propagates untouched. On the pekko row, where cancellation is
//       impossible, an abandoned wait resumes and the loop continues — the
//       spec's DECLARED divergence, asserted as a no-op.
//   L5  The failure reported when the loop gives up is always the LAST
//       attempt's own `TypesafeException` — there is no synthetic "retries
//       exhausted" member to report instead.
//   L6  The jitter draw comes from `scala.util.Random` suspended in
//       `CIO.defer` — the pure `RetryPolicy.jittered` bounds are proven
//       against a supplied `u`; loop-level tests use jitter-0 policies where
//       a wait's exact value must be known.
// ============================================================================

private object RetryLoopTypeContract {

  // surface witness — stops compiling if the promoted surface drifts
  val runSig: (RetryPolicy, Clock) => CIO[Int] => CIO[Int] =
    (policy, clock) => operation => Retry.run(policy, clock)(operation)
}
