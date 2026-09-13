# Session goal, 13.09.2026

## Definition of done

One real SPARQL SELECT DISTINCT query runs end to end through
`YannakakisOpExecutor` against a real in-memory Jena graph and returns
results identical to the naive oracle. Committed, tests green.

That is the claim Chapter 4 has to support. Everything else is optional.

## In scope, in this order

1. Verify the ARQ integration actually executes. Write an end-to-end test
   that registers the factory via `QC.setFactory`, runs a query through
   the normal ARQ entry point, and asserts the Yannakakis path was taken
   (not silently falling back to the default executor).
2. Differential test: same query, Yannakakis+ vs naive oracle, canonical
   result comparison. At least 3 shapes: path query, star query,
   query with a non-output join variable.
3. Decide and act on the superseded scaffold `YannakakisQueryEngine` and
   `YannakakisTransform`. Delete if dead, or document why kept.
4. Write `docs/implementation-inventory.md`: every class in
   `at.ac.tuwien.thesis.yannakakis` with a one-line responsibility, the
   data flow from `OpBGP` to result bindings, and every deviation from
   Wang et al. with the reason.

## Out of scope today

Cost-based join tree selection. TDB2. Benchmarking. Performance tuning.
OPTIONAL handling. Anything touching multiset semantics.

## Decisions log

Append one line per non-obvious choice made today, with the reason.
This file feeds Chapter 4.

- Step 1 (verify ARQ integration executes via `QC.setFactory` + normal ARQ
  entry point + Yannakakis-path assertion): no new test added.
  `YannakakisOpExecutorTest#executorActuallyFiresOnAcyclicBgp` and
  `DifferentialTest.Wrappers#distinct` already do exactly this (register via
  `YannakakisOpExecutor.register()` → `QC.setFactory`, run through
  `QueryExecutionFactory.create(...).execSelect()`, assert
  `invocations() >= 1`; the latter uses an actual `SELECT DISTINCT` query
  against stock ARQ as oracle). Confirmed green via
  `mvn test -Dtest=YannakakisOpExecutorTest,DifferentialTest` (29/29 passed).
  Adding a separate test would have duplicated existing coverage.