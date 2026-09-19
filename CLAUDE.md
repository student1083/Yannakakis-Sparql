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
   with nothing multiplicity-sensitive in between). Never rewrites the tree. It also records,
   per BGP variable, the conjuncts of a *directly* enclosing `OpFilter` (`Filter(BGP)`, not a
   filter over a join/other operator) that constrain exactly that one variable and can be
   decided from a single candidate node for it — equality/inequality to a constant, numeric
   range comparisons, `REGEX`, `DATATYPE`/`LANG`/`LANGMATCHES`, and `&&`/`||`/`!` combining
   them, recognised by an explicit whitelist (`isSingleNodeEvaluable`) so an unrecognised form
   (EXISTS, aggregates, custom/property functions, non-deterministic functions, a second
   variable) is simply not extracted — never incorrectly. `YannakakisOpExecutor.matchTriple`
   uses these purely as a size hint: rows failing a conjunct are never turned into `Relation`
   rows, and an equality to a constant becomes a bound `Graph.find` position instead of a
   post-filter when doing so is safe (see step 7). The real `FILTER` still runs, unchanged,
   above the BGP in the ARQ plan; SPARQL FILTER semantics are never reimplemented, only
   pre-empted for rows that would fail it regardless.
5. **`QueryClassifier`** — classifies an acyclic BGP against `O` as relation-dominated ⊂
   free-connex ⊂ acyclic and roots the join tree accordingly (dominating relation / free-connex
   tree with connex subtree Tn / GYO root). Given cardinality estimates
   (`classify(h, O, estimates)`) it applies the two §5.2 heuristics: the root is the largest
   candidate of the class (dominating relations / Tn / all nodes) that mentions an output
   variable, and GYO runs with a weighted ear order (small ears first, large witnesses first).
   The two-argument `classify` is structure-only and unchanged.
5a. **`CardinalityEstimator`** — cheap per-triple-pattern estimate taken before materialising.
   `BoundedCountEstimator` (in-memory/generic: `Graph.find` counted up to
   `YannakakisSymbols.ESTIMATE_LIMIT`, saturating) and `Tdb2CardinalityEstimator` (TDB2:
   `stats.opt` predicate/type/total counts when present, else `NodeTupleTable.findAsNodeIds`
   counted without decoding). `forGraph` picks; `CARDINALITY_ESTIMATOR` in the context
   overrides. Nothing beyond `Graph.find` is reachable without the TDB2 cast — see the
   interface javadoc.
5b. **`DimensionFusion`** — Wang et al. §5.1: leaf siblings under R are replaced by their join
   (Cartesian product if disjoint) when est(R) ≥ θ · ∏ est(leaf), θ =
   `YannakakisSymbols.FUSION_RATIO` (default 1.0, the paper's break-even; ∞ turns it off).
   Only within the absorbable group (A_i ∩ O ⊆ A_R) or the connex-compatible group
   (A_i ∩ A_R ⊆ O), so Theorem 3.11 / the free-connex guarantee survive.
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
substitutes the binding, estimates each substituted pattern, classifies the substituted pattern
against `O` restricted to the free variables with the estimates (cached per binding shape +
estimate ranking), applies `DimensionFusion` (per binding, needs magnitudes), matches each
triple against the active `Graph`, joins fused leaves, runs
`YannakakisPlusEvaluator.evaluate`, applies `distinct()` iff collapsible, and emits each π_O row
`count` times layered onto the incoming binding. If the BGP is cyclic (or there's no active
graph), it delegates to stock ARQ (`super.execute(...)`) unchanged. The earlier
`YannakakisQueryEngine`/`YannakakisTransform` algebra-rewriting scaffold was deleted (step 3).

`matchTriple` additionally takes the analyzer's per-variable filter conjuncts (step 4) and the
`ExecutionContext`: for each position, an equality-to-constant conjunct becomes the bound
`Graph.find` term instead of `Node.ANY` when the constant's RDF-term identity is guaranteed to
coincide with SPARQL value equality (IRIs, blank nodes, literals with no datatype / `xsd:string`
/ a language tag) — value-based datatypes (numeric, boolean, date/time) are excluded, since two
of their nodes can be SPARQL-equal without being identical terms (`"1"` vs `"01"` as
`xsd:integer`), so pushing those into `find()` could silently miss matches. Every conjunct
(pushed down or not) is also checked per candidate node via `Expr.isSatisfied` — the same call
ARQ's own `FILTER` uses — before the row is ever added to the `Relation`; since a conjunct
mentions no variable but that one position's, this can never reject a row the real `FILTER`
above the BGP would keep. That real `FILTER` still runs there, unchanged.

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
- `DimensionFusionTest` does the same for the estimate-aware classification and for fusion:
  random estimates / corpus estimates must keep the class, both structural oracles and the
  oracle bag (θ = 1 and θ = 0); the paper's R1(a) ⋈ R2(a,b) ⋈ R3(b) example, group separation
  and the §5.2 root rules are pinned; an end-to-end part runs the executor against stock ARQ on
  a star schema where fusion fires (`fusions()`), with θ and the estimator varied via context.
- `CardinalityEstimatorTest` checks both estimators against known counts, including a real
  TDB2 store under `target/tdb2-estimator/` with a generated `stats.opt` (never deleted: TDB2
  memory-maps its files and Windows refuses).
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
  it's the main defense against regressions the fixed example queries wouldn't catch. A
  `SingleVarFilterPushdown` group covers the step-4/step-7 filter pre-filtering: equality to a
  safe constant (pushed into `Graph.find`), equality to a numeric constant with an alternate
  lexical form in the data (`"01"^^xsd:integer = 1`, pinned so a naive node-identity pushdown
  regression would be caught), `REGEX`, a numeric range split from a top-level `&&`,
  `DATATYPE`/`LANG`, and a two-variable filter (never pushed down, still correct).
  `AlgebraContextAnalyzerTest` separately checks the conjunct extraction itself: splitting
  `&&`, rejecting a two-variable or unwhitelisted (e.g. `BOUND`) conjunct, and that a filter
  not *directly* over a BGP (over a join instead) extracts nothing for either side.

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
  does transfer to RDF and is implemented in `DimensionFusion` (step 11);
  estimates shape plans only and must never change an answer, so the
  differential tests stay the gate for any change to it.
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
  `BindingFactory.binding(Var, Node)` also exists (a single-pair binding).
- `ExprVars.varsMentioned` works with `opFilter.getExprs()`.
- `Expr.isSatisfied(Binding, FunctionEnv)` never throws (errors/EBV -> false);
  `Expr.eval(Binding, FunctionEnv)` can throw `ExprEvalException`. `Expr.isConstant()`/
  `getConstant()`, `isVariable()`/`asVar()`/`getVarsMentioned()` exist directly on `Expr`.
  `ExecutionContext implements FunctionEnv`.
- `ExprFunction1.getArg()`, `ExprFunction2.getArg1()`/`getArg2()`,
  `ExprFunctionN.getArg(int)` (1-indexed, returns `null` past the actual arg count) and
  `numArgs()` exist; `E_Regex extends ExprFunctionN` with args (text, pattern, [flags]),
  `E_Equals`/`E_NotEquals`/`E_LessThan(OrEqual)`/`E_GreaterThan(OrEqual)`/`E_LogicalAnd`/
  `E_LogicalOr`/`E_LangMatches` extend `ExprFunction2`, `E_LogicalNot`/`E_Lang`/`E_Datatype`
  extend `ExprFunction1`.
- `NodeValue.asNode()` forces a `NodeValue` to its `Node` form; `NodeValue.sameValueAs` is
  SPARQL `=` (value equality), distinct from `Node.equals` (term identity) — the two coincide
  only for IRIs, blank nodes, and string-like literals (no datatype / `xsd:string` / a
  language tag), not for numeric/boolean/date-time datatypes.
- `opProject.getVars()` returns `List<Var>`.
- `Query.setDistinct(boolean)` / `isDistinct()` exist; `Query.toString()`
  re-serializes to SPARQL text.
- `OpWalker.walk(Op, OpVisitor)` with an `OpVisitorBase` subclass visits
  every `OpBGP`; `Algebra.compile(Query)` gives the unoptimised `Op`.
- `Var.getVarName()` returns the bare name.
- `Context.get(Symbol)`, `Context.getLong(Symbol, long)` (accepts String/Integer/Long),
  `Context.set`/`unset`/`isDefined`.
- TDB2 (verified against `jena-tdb2-6.0.0-sources.jar`): `GraphTDB extends GraphViewStorage`
  with public `getDSG()`, `getNodeTupleTable()`, `getGraphName()` (null for the default
  graph); `GraphViewSwitchable.getBaseGraph()` unwraps to the current `GraphTDB`;
  `DatasetGraphTDB.getLocation()` is the storage dir (`Data-000N`), `stats.opt` lives there
  or in its parent (container); `NodeTupleTable.findAsNodeIds(Node...)` takes `Node.ANY`
  for wildcards, throws on variables, returns a null-iterator for unknown terms, and needs 3
  args on the triple table / 4 on the quad table (`chooseNodeTupleTable`); `Location`
  (`org.apache.jena.dboe.base.file`) has `getDirectoryPath()`, `isMem()`;
  `org.apache.jena.dboe.sys.Names.optStats`; `Stats.gather(Graph)` → `StatsCollector.results()`,
  `Stats.format(StatsResults)` → `Item`, `Stats.write(String, StatsResults)`.
  `ReorderWeighted` keeps its `StatsMatcher` private — no getter; the dataset's context holds
  only the executor factory. TDB2's own executor still wins over `register()` (tdb2-audit F1).
- SSE: `SSE.readFile(String)`/`SSE.parse(String)` → `Item`; `Item.isTagged(String)`,
  `getList()` (an `ItemList`, `Iterable<Item>`, with `size()`, `get(i)`, `car()`, `cdr()`),
  `isNode()`, `getNode()`, `isList()`, `asLong()`; `Item.find(ItemList, String)`.

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