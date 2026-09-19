# typesafe4s-compat-ce

This module implements the [kyo-compat](https://github.com/getkyo/kyo/tree/main/kyo-compat) API with `cats.effect.IO`
and fs2. typesafe4s defines its runtime against `kyo.compat`, so the `-ce` cells of `typesafe4s-client` and every
downstream matrix compile and run against this implementation.

## Provenance

Kyo removed its Cats Effect integrations in `1.0.0-RC6` (see [kyo#1779](https://github.com/getkyo/kyo/pull/1779)) and
moved community bindings outside the project (see [kyo#1840](https://github.com/getkyo/kyo/pull/1840)). Without a
vendored copy there is no `ce` row.

These sources were taken from **[Sage](https://github.com/ghostdogpr/sage)'s `sage-compat-ce` module**, which in turn
vendors the last upstream Cats Effect binding from Kyo commit
[`eae31e1d`](https://github.com/getkyo/kyo/tree/eae31e1d39d4b8ff2df168272e60b38d9e9dd502/kyo-compat/bindings/ce).
Those sources match `io.getkyo:kyo-compat-ce_3:1.0.0-RC5`.

Carried over from Sage's copy, and therefore already deviating from the last upstream release:

- the `shared` and `jvm` source trees are merged, because only the JVM is supported;
- brace-based formatting, and several corrected comments;
- **`CIO.async` uses `IO.async` with a cancellation token rather than `IO.async_`**, so a timeout or cancellation can
  stop waiting for a callback. This matters directly for typesafe4s: the HTTP transport and the retry loop both need
  a cancellable wait. Cancellation does not retract an operation already started — see
  [`docs/architecture-analysis.md`](../docs/architecture-analysis.md) §5.1.

typesafe4s has made **no further changes** to these sources. Kyo is licensed under
[Apache 2.0](https://github.com/getkyo/kyo/blob/main/LICENSE), the same licence as typesafe4s and Sage.

## Keeping it in sync

The binding depends only on Cats Effect and fs2, so upgrading Kyo does not change this module directly. A Kyo release
can still add operations to `kyo.compat` or change their behaviour. The cross-binding conformance suite bundled in
`kyo-compat-plugin` detects exactly that, and the `ceConformance` matrix runs it against this module:

```bash
sbt conformanceCe
```

CI runs it as part of `testUnit`. When the suite reports a difference, port the matching update from another binding —
for example [`bindings/future`](https://github.com/getkyo/kyo/tree/main/kyo-compat/bindings/future) — and translate its
operations to Cats Effect.

> A failure here is an upstream contract change, not a typesafe4s bug. Fix it in this module; do not work around it in
> the client.
