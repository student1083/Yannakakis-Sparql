# yannakakis-sparql

Prototype for the master's thesis *Structure Guided Query Evaluation in SPARQL*:
integrating the **yannakakis+** algorithm into Apache Jena's ARQ engine as a deep
but surgical rewriting of **acyclic basic graph patterns (BGPs)**.

## Requirements

- **JDK 21** (Jena 6.x requires Java 21+). Use Eclipse Temurin.
- **Maven** (or the Maven bundled with IntelliJ).
- **Apache Jena 6.x** — pulled automatically via Maven (`apache-jena-libs`).

## Windows setup

In PowerShell:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install Git.Git
winget install JetBrains.IntelliJIDEA.Community
winget install Apache.Maven
```

Reopen PowerShell, then verify:

```powershell
java -version    # 21.x, 64-Bit
mvn -version
git --version
```

If `JAVA_HOME` is unset, point it at the Temurin install dir via the system
environment variables dialog.

## Build & run the smoke test

In IntelliJ: *Open* this folder, let Maven import, then run `SmokeTest`.

Or from the command line:

```powershell
mvn compile exec:java
```

A one-row result plus "Jena ... is working" confirms the toolchain.

## Project layout

```
src/main/java/at/ac/tuwien/thesis/yannakakis/
  SmokeTest.java               # toolchain check (run this first)
  YannakakisQueryEngine.java   # custom ARQ engine + registration (the hook)
  YannakakisTransform.java     # GYO + join tree + rewrite  <-- June work goes here
```

## Integration approach

This project depends on `jena-arq` and plugs into ARQ's documented extension
points (`QueryEngineFactory` + `QueryEngineRegistry`, overriding
`QueryEngineMain.modifyOp`). No fork of Jena is required — the rewriting operates
directly on the `Op` algebra tree, which is exactly the "deep but surgical"
integration described in the thesis.

To switch the engine on, call `YannakakisQueryEngine.register()` at startup;
`unregister()` restores stock ARQ (handy for A/B benchmarking later).

## Next milestones

- **June:** implement `YannakakisTransform` (acyclicity test, join tree, rewrite).
- **July:** fix bugs, start benchmarking.
- **August:** load SP2Bench / BSBM / DBpedia into TDB2 (needs the Jena binary
  distribution for the `tdb2.tdbloader` CLI), build the Python benchmark harness.
