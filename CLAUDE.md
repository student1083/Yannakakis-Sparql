# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Prototype for the master's thesis *Structure Guided Query Evaluation in SPARQL*: integrating a
Yannakakis-style join algorithm into Apache Jena's ARQ engine to evaluate **acyclic basic graph
patterns (BGPs)** via GYO decomposition + semijoin reduction instead of ARQ's default nested-loop
join, while falling back to stock ARQ for anything cyclic. All code lives under
`at.ac.tuwien.thesis.yannakakis` (`src/main/java/...`, tests under `src/test/java/...`).

Requires JDK 21 (Jena 6.x) and Maven; Apache Jena 6.0.0 is pulled via `apache-jena-libs`.

## Commands

```powershell
mvn compile              # build
mvn test                 # run the full test suite (JUnit 5 / surefire)
mvn test -Dtest=DifferentialTest                       # run one test class
mvn test -Dtest=DifferentialTest#randomAcyclicBgpsMatchStockArq   # run one test method
mvn compile exec:java    # run the toolchain smoke check (SmokeTest is the configured mainClass)
```

There are no lint/format plugins configured — `mvn test` (surefire) is the only gate.

Demo classes (`GyoDemo`, `HypergraphDemo`, `AlgebraExplorer`, `YannakakisArqDemo`,
`YannakakisIntuitionDemo`) are runnable via `mvn exec:java -Dexec.mainClass=at.ac.tuwien.thesis.yannakakis.<Name>`
but aren't wired into any build target; they're standalone `main()` scratch tools for manual
inspection of the pipeline (printing hypergraphs, algebra trees, join-tree structure, etc.), not
part of the tested surface.

## Architecture

### The pipeline (GYO decomposition → semijoin reduction → join)

1. **`QueryHypergraph`** — builds a hypergraph from a Jena `BasicPattern`: variables are vertices,
   triple patterns are hyperedges.
2. **`GyoReduction`** — runs the GYO ear-removal algorithm over the hypergraph to test
   α-acyclicity; if acyclic, produces a **`JoinTree`**.
3. **`JoinTree`** — rooted tree of hyperedges (one node per triple pattern), with a
   `satisfiesRunningIntersection()` self-check used as the correctness oracle in tests.
4. **`Relation`** — immutable tuple-set (`Var → Node` bindings) with `semijoin`/`join`
   operations; the runtime data representation matched triple patterns are converted into.
5. **`YannakakisEvaluator`** — given a `JoinTree` and one `Relation` per edge, runs: upward
   semijoin pass → downward semijoin pass (together = the full reducer) → upward join pass,
   returning the joined `Relation` at the tree root.

### ARQ integration — two paths exist, only one is live

- **`YannakakisOpExecutor` is the actual, working, tested integration point.** It's an
  `OpExecutor` subclass registered globally via `QC.setFactory(ARQ.getContext(), FACTORY)`
  (`register()`/`unregister()`). It intercepts each `OpBGP`, checks acyclicity, and — if
  acyclic — substitutes the incoming binding into the pattern, matches each triple against the
  active `Graph`, runs the GYO → `JoinTree` → `YannakakisEvaluator` pipeline above, and merges
  result rows back into a `QueryIterator`. If the BGP is cyclic (or there's no active graph), it
  delegates to stock ARQ (`super.execute(...)`) unchanged. This is the path every real test
  (`YannakakisOpExecutorTest`, `DifferentialTest`) exercises.
- **`YannakakisQueryEngine` + `YannakakisTransform` are an earlier, abandoned integration
  attempt** — a `QueryEngineMain` subclass meant to rewrite the whole `Op` algebra tree via
  `Transformer.transform(new YannakakisTransform(), op)`, registered through
  `QueryEngineRegistry`. `YannakakisTransform.transform(OpBGP)` is still a literal no-op
  (`return opBGP;`). **Ignore the "Integration approach" and "Next milestones" sections of
  README.md** — they describe this original plan, which was superseded by the `OpExecutor`
  approach once the real GYO/join-tree/evaluator logic (above) was built; the README was not
  updated to match. Nothing in the codebase calls `YannakakisQueryEngine.register()`.

When touching the join/evaluation logic, `YannakakisOpExecutor` is the class that wires it into
ARQ — not `YannakakisQueryEngine`.

### Testing strategy

There are no external RDF/query fixture files — every model and query is built inline in Java
(Jena `Model`/`BasicPattern` API, text-block SPARQL strings).

- `QueryHypergraphTest`, `GyoReductionTest`, `YannakakisEvaluatorTest` are unit-level, checking
  each pipeline stage directly (the evaluator's oracle is a locally-defined naive fold-join).
- **`DifferentialTest` is the primary correctness harness.** For each query it runs stock ARQ
  and `YannakakisOpExecutor`-registered ARQ against the *same* in-memory model and asserts the
  (canonicalized, order-independent unless `ORDER BY` is used) result bags match. It also asserts
  via `YannakakisOpExecutor.invocations()` whether the Yannakakis path actually fired (acyclic
  cases) or fully delegated (cyclic cases, including a `UNION` of an acyclic + cyclic branch).
  It includes a **randomized/property-based suite** (fixed seed `20260706L`, 30 rounds) that
  generates random acyclic BGPs and matching random graphs and diffs both engines — this is the
  test to extend when changing core join/semijoin behavior, since it's the main defense against
  regressions the fixed example queries wouldn't catch.

## Hard rules

- Integration happens at EXECUTION TIME via `OpExecutor` interception.
  Never via algebra rewriting / `Transform` on the algebra tree.
  ARQ has no native semijoin operator, so semijoin passes must run at
  execution time. This distinction appears throughout the thesis.
- A BGP's answer under SPARQL semantics is already a set of mappings, so
  the evaluator's set semantics (`Relation` as `HashSet`) is exact for
  every SPARQL query, not only `SELECT DISTINCT` — this is not a
  restriction we impose, only a fact about full-BGP evaluation. The
  restriction becomes real once we project early (duplicates then need
  multiplicities to reconstruct bag semantics correctly). No multiset/bag
  support until step 10 lifts this via counting-semiring annotations.
- Cycle elimination, aggregation elimination and semi-join elimination in
  Wang et al. section 5.1 all rely on primary-key or foreign-key
  constraints. RDF has no schema-level keys, so these do not transfer. Do
  not implement them. (Note in passing that `owl:FunctionalProperty` and
  `owl:InverseFunctionalProperty` are a partial analogue, but they are out
  of scope.)
- Fusion of dimension relations is cardinality-based, not key-based. It
  does transfer to RDF and is in scope. See step 11 of
  docs/claude-code-plan.md.
- RDF triples limit hyperedge rank (arity) to 3. "Width" is reserved for
  hypertree width in this project — never use it for hyperedge arity.

## Verified Jena 6 API (do not guess these)

- `OpExecutor` constructor takes `ExecutionContext`.
- Correct override: `execute(OpBGP, QueryIterator)`.
- `OpExecutor`, `OpExecutorFactory`, `QC` live in
  `org.apache.jena.sparql.engine.main`.
- `QC.setFactory(Context, OpExecutorFactory)` exists.
- `OpExecutorFactory` has `create(ExecutionContext)`.
- `execCxt.getActiveGraph()` returns `Graph`.
- `QueryIterPlainWrapper.create(Iterator, ExecutionContext)` exists.
- `QueryIterRoot` exists for instanceof checks.
- `BindingFactory.builder(Binding parent)` exists.
  `BindingFactory.empty()` is the empty root binding.
- `ExprVars.varsMentioned` works with `opFilter.getExprs()`.
- `opProject.getVars()` returns `List<Var>`.

If an API is not in this list, read the Jena source before using it.
Do not invent method signatures.

## Code conventions

- `LinkedHashSet` / `LinkedHashMap` everywhere in graph and hypergraph
  structures, for deterministic iteration order.
- `IdentityHashMap` when keying by object identity.
- Never return null where an empty collection is correct.
- Declare the most specific type available.
- Never compare results via `QuerySolution.toString()`. Canonical form:
  sort variables alphabetically within a row, then sort the rows.
- Keep correctness (from the bottom-up fold) separate from
  output-sensitivity (from the semijoin reduction).

## Working style

- Always run `mvn test` after a change and report the exact output.
- Small commits, one per working milestone.
- Do not refactor code you were not asked to touch.
- Do not write thesis text. Write code and factual notes only.