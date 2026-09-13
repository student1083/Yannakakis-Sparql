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

### The pipeline (GYO decomposition → classification w.r.t. O → Yannakakis+)

1. **`QueryHypergraph`** — builds a hypergraph from a Jena `BasicPattern`: variables are vertices,
   triple patterns are hyperedges.
2. **`GyoReduction`** — runs the GYO ear-removal algorithm over the hypergraph to test
   α-acyclicity; if acyclic, produces a **`JoinTree`**. The ear-removal core (`reduce`, over
   abstract `id → vars` maps) is shared with the classifier.
3. **`JoinTree`** — rooted tree of hyperedges (one node per triple pattern), with a
   `satisfiesRunningIntersection()` self-check used as the correctness oracle in tests.
4. **`AlgebraContextAnalyzer`** — read-only walk of the ARQ `Op` tree, run once per query
   execution; per `OpBGP` (by object identity) it records the output variables `O` the rest of
   the plan needs and whether the BGP's row multiplicities are collapsible (a `DISTINCT` above
   with nothing multiplicity-sensitive in between). Never rewrites the tree.
5. **`QueryClassifier`** — classifies an acyclic BGP against `O` as relation-dominated ⊂
   free-connex ⊂ acyclic and roots the join tree accordingly (dominating relation / free-connex
   tree with connex subtree Tn / GYO root).
6. **`Relation`** — immutable bag of tuples (`Var → Node` rows, each with a positive count; the
   counting semiring: `project` sums, `join` multiplies, `semijoin` keeps the left count,
   `distinct()` resets to 1) with `project`/`semijoin`/`join`.
7. **`YannakakisPlusEvaluator`** — Wang et al. Algorithms 1 and 2: first round (post-order
   absorb-or-semijoin with early projection onto `O ∪ join vars`, Theorem 3.11 short-circuit for
   relation-dominated queries) and second round (merge children into the dangling-free root,
   dropping non-output join variables no remaining neighbour needs; free-connex queries never
   reach the general-merge branch, asserted under `setInvariantChecks(true)`). Returns π_O with
   multiplicities.
8. **`YannakakisEvaluator`** — the classical three-pass version (upward semijoin → downward
   semijoin → upward join), untouched and kept as the differential oracle for the Plus
   evaluator. Not on the executor's path any more.

### ARQ integration

**`YannakakisOpExecutor` is the single integration point.** It's an `OpExecutor` subclass
registered globally via `QC.setFactory(ARQ.getContext(), FACTORY)` (`register()`/`unregister()`).
Its `exec` override runs the analyzer once per execution; `execute(OpBGP)` checks acyclicity and —
if acyclic — looks up `O` and the collapsible flag, then per incoming binding (`Stage`):
substitutes the binding, classifies the substituted pattern against `O` restricted to the free
variables (cached per binding shape), matches each triple against the active `Graph`, runs
`YannakakisPlusEvaluator.evaluate`, applies `distinct()` iff collapsible, and emits each π_O row
`count` times layered onto the incoming binding. If the BGP is cyclic (or there's no active
graph), it delegates to stock ARQ (`super.execute(...)`) unchanged. The earlier
`YannakakisQueryEngine`/`YannakakisTransform` algebra-rewriting scaffold was deleted (step 3).

### Testing strategy

There are no external RDF/query fixture files — every model and query is built inline in Java
(Jena `Model`/`BasicPattern` API, text-block SPARQL strings).

- `QueryHypergraphTest`, `GyoReductionTest`, `RelationTest`, `YannakakisEvaluatorTest`,
  `AlgebraContextAnalyzerTest`, `QueryClassifierTest` are unit-level, checking each pipeline
  stage directly (the classical evaluator's oracle is a locally-defined naive fold-join).
- `YannakakisPlusEvaluatorTest` diffs the Plus evaluator against π_O of the classical
  evaluator (bag-exact, multiplicities included) on fixed shapes, on every BGP of the
  `OutputVariableSafetyTest` corpus (O from the analyzer, relations from the corpus graph), and
  on 200 random acyclic BGPs; it asserts Theorem 3.11 (one node, zero semijoins) on every
  relation-dominated case and zero general merges on every free-connex case.
- **`DifferentialTest` and `OutputVariableSafetyTest` are the end-to-end correctness harness.**
  For each query they run stock ARQ and `YannakakisOpExecutor`-registered ARQ against the *same*
  in-memory model and assert the canonicalized result bags match — sorted row lists that keep
  duplicates, so multiplicities must agree (order-sensitive when `ORDER BY` is used, row count
  only for a bare `LIMIT`). `OutputVariableSafetyTest` runs its 62-query corpus and a
  `SELECT DISTINCT` variant of every non-DISTINCT query; `DifferentialTest` also asserts via
  `YannakakisOpExecutor.invocations()` whether the Yannakakis path actually fired (acyclic
  cases) or fully delegated (cyclic cases, including a `UNION` of an acyclic + cyclic branch),
  and includes a **randomized/property-based suite** (fixed seed `20260706L`, 30 rounds, plus a
  projected variant) that generates random acyclic BGPs and matching random graphs and diffs
  both engines — this is the test to extend when changing core join/semijoin behavior, since
  it's the main defense against regressions the fixed example queries wouldn't catch.

## Hard rules

- Integration happens at EXECUTION TIME via `OpExecutor` interception.
  Never via algebra rewriting / `Transform` on the algebra tree.
  ARQ has no native semijoin operator, so semijoin passes must run at
  execution time. This distinction appears throughout the thesis.
- Bag semantics via the counting semiring (step 10). A BGP's answer is a
  set of mappings, but the moment we project early (Yannakakis+ does, onto
  `O ∪ join vars`) duplicates need multiplicities to reconstruct SPARQL bag
  semantics. `Relation` therefore annotates every tuple with a count:
  `project` sums, `join` multiplies, `semijoin` keeps the left count.
  Never de-duplicate inside the algorithms. DISTINCT enters in exactly one
  place — `Relation.distinct()` at the end of `Stage.nextStage`, gated by
  the analyzer's per-BGP `countsCollapsible` flag — the algorithms never
  branch on it. `OpReduced` is not a DISTINCT (stock ARQ keeps some
  duplicates under REDUCED and the differential tests would see it).
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
- `Query.setDistinct(boolean)` / `isDistinct()` exist; `Query.toString()`
  re-serializes to SPARQL text.
- `OpWalker.walk(Op, OpVisitor)` with an `OpVisitorBase` subclass visits
  every `OpBGP`; `Algebra.compile(Query)` gives the unoptimised `Op`.
- `Var.getVarName()` returns the bare name.

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