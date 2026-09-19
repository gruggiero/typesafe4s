# Ring 4 — Stainless formal verification

A **separate, self-contained sbt build**. It is not a subproject of the root build and cannot be: Stainless
0.9.9.3 pins its frontend to Scala 3.7.2 while the rest of the repository is on 3.9.0, and TASTy is only
backward compatible. That makes this module a **leaf by construction**, which is exactly what the mirror-module
pattern needs — a kernel can never accidentally depend on production code.

> **A kernel is a MODEL. On its own it proves nothing about shipped code.**
> What makes it evidence is the Ring 2 **bridge property** that runs the production function and the kernel on
> the same generated input. A kernel without a bridge is a proof about something nobody runs.

## Layout

```
verified/
  build.sbt                     this build; also merges ScalaZ3 into the Stainless plugin jar
  project/plugins.sbt           loads sbt-stainless from project/lib (not on Maven Central)
  project/lib/                  sbt-stainless.jar            — untracked, installed by setup
  stainless/                    local Maven repo, ~83 MB     — untracked, installed by setup
  unmanaged/scalaz3_3-*.jar     native Z3 via ScalaZ3        — untracked, locally built
  .ring4/                       merged plugin jar + last-run.log
  src/main/scala/typesafe4s/verified/
                                the kernels
```

## Setup (once per checkout)

```bash
scripts/setup-stainless.sh
```

Downloads and checksums the official `sbt-stainless.zip` bundle. It does **not** install the ScalaZ3 jar — that
is a locally built artifact; see `unmanaged/README.md`. Without it Stainless falls back to `smt-z3`, which needs
a `z3` binary on PATH and is slower on hard conditions.

## Running

```bash
scripts/ring4.sh
```

Never `sbt ring4` bare from this directory in CI: **Stainless reports a refuted verification condition as a
warning and sbt still exits 0.** The script reads the summary line and turns invalid or unknown conditions into
a non-zero exit. It also enforces a wall-clock timeout, because Stainless 0.9.9.3 has no working per-condition
timeout and a hard condition hangs forever.

It prints which solver ran. If it says `smt-z3` rather than `nativez3`, the ScalaZ3 jar is missing.

## What to verify here

Ring 4 earns its keep on **pure, algorithmic invariants over values** — statements quantified over all inputs
that no finite set of tests can establish.

In this SDK that means:

- **the retry schedule** — growth, ceiling, jitter bounds, and the budget invariant (`RetrySchedulerKernel`)
- **the token-budget division** — every question in exactly one batch, every batch fitting

It does **not** mean anything touching the carrier, the transport, a facade or a backend row. Those are Ring 5's
business, and a model of them would be a model of the wrong thing.

Before reaching for Ring 4, ask whether the invariant can be pushed into the **type system** instead. Several of
this SDK's constraints already are — Score levels 2–10, Choice options ≤ 255, non-empty question sets are all
`inline if` + `compiletime.error`, discharged by Ring 0 and a compile-negative test. A compile error is stronger
evidence than a proof about a model, and costs nothing to run.

## Writing a kernel

1. **Mirror, do not import.** The kernel restates the algorithm in PureScala. It cannot reference production
   code — different Scala version, and no effects, no carrier, no string interpolation.
2. **Use `BigInt`,** not `Int` or a duration type. Stainless reasons about unbounded integers natively; machine
   arithmetic drags in overflow conditions that obscure the property you care about.
3. **State the property as `ensuring`,** with `require` for what the caller guarantees. Recursive functions need
   `decreases`.
4. **Add the Ring 2 bridge property** in the production module's test sources: generate an input, run the real
   function and the kernel, assert they agree. Without it, step 3 proved nothing about the SDK.
5. **If verification fails, fix the kernel or the implementation — never weaken the contract.** A contract
   relaxed until it passes is a contract that says nothing.

## When verification hangs

`scripts/ring4.sh` kills the run after `RING4_TIMEOUT` seconds (default 1800) and prints the last progress line,
which names the condition that was being discharged. Usual causes, in order of likelihood:

- a recursive function with no `decreases`, or one Stainless cannot see terminates
- non-linear arithmetic (multiplying two variables) — Z3 handles it poorly
- an `ensuring` that is simply false, where the solver searches rather than finding a counterexample

Narrow the kernel until it verifies, then widen it back a step at a time.

## Ring numbering

This project's rings: 0 compile · 1 lint · 1.5 architecture · 2 property tests · 3 mutation · **4 formal** ·
5 cross-backend parity · 8 adversarial review. Ring 5 is parity rather than telemetry here — see
`openspec/config.yaml`.
