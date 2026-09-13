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
```

## Integration approach

`YannakakisOpExecutor` is registered as ARQ's `OpExecutor` via `QC.setFactory(...)` and
intercepts each `OpBGP` at execution time: acyclic BGPs run through GYO decomposition +
semijoin reduction + join; anything cyclic falls back to stock ARQ unchanged. See CLAUDE.md's
Architecture section for the full pipeline and data flow.
