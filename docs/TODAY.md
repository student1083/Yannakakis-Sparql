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
- Step 2 (differential test: path / star / non-output join variable shapes): path and star
  shapes were already covered (`Shapes.path()`, `Shapes.star()`). A non-output join variable was
  only covered for a path shape (`Wrappers.distinct()`); added
  `Shapes.starWithNonOutputJoinVariable()` (SELECT DISTINCT ?n over a star BGP joined on ?p) to
  cover the star + hidden-join-variable combination. Confirmed green via
  `mvn test -Dtest=DifferentialTest`.
- Step 3 (decide on the superseded `YannakakisQueryEngine`/`YannakakisTransform` scaffold):
  deleted both files. Repo-wide search found zero inbound references outside the two files
  themselves — no imports, no test coverage, no `pom.xml` wiring, and `register()` (via
  `QueryEngineRegistry`) is never called anywhere; `YannakakisTransform.transform(OpBGP)` was
  confirmed a literal no-op (`return opBGP;`). They also contradict CLAUDE.md's hard rule that
  integration must happen via `OpExecutor` at execution time, never via algebra
  `Transform` rewriting — so keeping them wasn't a live option. Updated README.md's project
  layout, "Integration approach", and "Next milestones" sections, which only described this
  abandoned plan.
- Step 4 (write `docs/implementation-inventory.md`): documented all 12 classes under
  `at.ac.tuwien.thesis.yannakakis` (6 pipeline classes + 6 standalone `main()` demos, the latter
  flagged as not part of the tested surface), a step-by-step `OpBGP` → bindings trace with
  file:line citations verified directly against current source, and 10 deviations from Wang et
  al. (set semantics, no PK-FK/Dimension Fusion, rank-3 hyperedges, no cost-based join tree
  selection, arbitrary isolated-component anchoring, execution-time-only integration, full
  fallback for any cyclic BGP, no join-tree caching across bindings, no OPTIONAL support in the
  evaluator, no RDF-specific match optimizations). Called out that CLAUDE.md's architecture
  section is stale re: `YannakakisQueryEngine`/`YannakakisTransform` (describes them as
  abandoned-but-present; they were deleted in step 3) so the new doc reflects current reality
  instead. `mvn test` not re-run (docs-only change, no code touched); last known-green run was
  step 3's `mvn test -Dtest=DifferentialTest`.