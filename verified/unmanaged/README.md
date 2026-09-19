# Native Z3 for Stainless (Ring 4)

`scalaz3_3-4.13.4.jar` in this directory gives Stainless the **native Z3
interface**. Without it Inox falls back to `smt-z3` — Z3 driven over SMT-LIB on
a subprocess — which is slower and can leave hard verification conditions
`unknown` that native Z3 discharges.

| Solver     | ~110 VCs | Notes                        |
|------------|----------|------------------------------|
| `smt-z3`   | ~2.5 s   | needs a `z3` binary on PATH  |
| `nativez3` | ~1.2 s   | JNI, this jar                |

`scripts/ring4.sh` prints which solver ran and warns when it is not `nativez3`.
Stainless also prints it at the end of a run:

```
[info] Verification pipeline summary:
[info]   @extern, cache, anti-aliasing, choose injection,
[info]   nativez3, non-batched
```

## Provenance

This jar is **not** part of the `sbt-stainless.zip` release bundle that
`scripts/setup-stainless.sh` installs, and it is not published to Maven
Central. It is built from [epfl-lara/ScalaZ3](https://github.com/epfl-lara/ScalaZ3)
against a pre-built Z3 4.13.4, with two patches to `Z3Wrapper.java` (below).
It bundles:

- `lib-bin/libz3.so`, `lib-bin/libz3java.so` — Z3 4.13.4 natives
- `lib-bin/libscalaz3.so` — the ScalaZ3 C wrapper
- `com/microsoft/z3/*.class` — Z3's Java bindings
- `z3/*.class` — the ScalaZ3 wrapper, compiled for Scala 3.7.2

It is Linux x86_64 / glibc 2.35+ only (Ubuntu 22.04 and newer). On any other
platform, delete it and let Ring 4 use `smt-z3` with a `z3` binary on PATH, or
rebuild it for that platform.

## Why it is merged into the compiler plugin

Inox probes for the native interface with `SolverFactory.hasNativeZ3`, which
calls `z3.Z3Wrapper.withinJar()`. The Stainless compiler plugin runs in a
classloader that searches **only its own jar** — not the compile classpath —
so the ScalaZ3 classes have to be physically inside
`stainless-dotty-plugin_3.7.2-0.9.9.3.jar`. `verified/build.sbt` does that
merge (`mergeScalaZ3IntoPlugin`), caching the result in `verified/.ring4/plugin/`
and rewriting `-Xplugin:` to point at it. The merge is idempotent and is redone
whenever `scripts/setup-stainless.sh` reinstalls the toolchain.

## Rebuilding the jar

Only needed for a different Z3 version, Scala version or platform.

1. **Get Z3 4.13.4** (pre-built — building from source is not required):

   ```bash
   mkdir -p ~/opt/z3-4.13.4 && cd ~/opt/z3-4.13.4
   wget https://github.com/Z3Prover/z3/releases/download/z3-4.13.4/z3-4.13.4-x64-glibc-2.35.zip
   unzip z3-4.13.4-x64-glibc-2.35.zip
   ```

   `bin/` must contain `z3`, `libz3.so`, `libz3java.so`, `com.microsoft.z3.jar`.

2. **Clone ScalaZ3** (`git clone https://github.com/epfl-lara/ScalaZ3.git`) and patch:

   - `project/build.properties` → `sbt.version=1.12.12` (the stock 1.7.3 breaks
     on modern JDKs).
   - `build.sbt` → `scalaVersion := "3.7.2"`, matching the Stainless frontend.
   - `project/Build.scala` → point `z3PreBuiltPath` at the `bin/` directory from
     step 1, set `z3BuildPath = z3PreBuiltPath`, and replace `z3Task` with one
     that only checks the pre-built files exist instead of cloning and building
     Z3 from source.
   - `src/main/java/z3/Z3Wrapper.java` → two fixes, both required under sbt:
     - the static initializer must **not** call `System.exit(1)` when
       `withinJar()` is false. sbt turns that into a `SecurityException`, which
       surfaces as `NoClassDefFoundError` and makes the class permanently
       unloadable — so `hasNativeZ3` returns false forever. Log and skip
       loading instead.
     - `loadFromJar()` must catch `Throwable`, not `Exception`:
       `System.load()` throws `UnsatisfiedLinkError`, which is an `Error`, and
       an escaping one poisons the static initializer the same way.

3. `sbt package`, then copy the resulting `scalaz3_3-4.13.4.jar` here.

4. Delete `verified/.ring4/plugin/` so the merged plugin jar is rebuilt.
