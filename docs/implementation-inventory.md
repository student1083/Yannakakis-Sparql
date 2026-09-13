# Implementation inventory

Snapshot of `at.ac.tuwien.thesis.yannakakis` as of step 10 (counting-semiring multiplicities,
Algorithm 2, executor on Yannakakis+; see `docs/today.md`). Written to support Chapter 4: every
class's responsibility, the data flow from `OpBGP` to result bindings, and every deviation from
Wang et al.'s Yannakakis+ with the reason for each. Earlier snapshots cited line numbers; those
went stale within a day, so this version cites methods.

## 1. Classes

| Class | Path | Responsibility |
|---|---|---|
| `YannakakisOpExecutor` | `YannakakisOpExecutor.java` | The single ARQ integration point: an `OpExecutor` registered via `QC.setFactory` that runs the algebra analysis once per execution (`exec` override), intercepts `OpBGP` execution, and for acyclic BGPs runs classify → match → `YannakakisPlusEvaluator` per incoming binding, emitting π_O rows with their multiplicities (collapsed to 1 when the analyzer says the BGP sits under a DISTINCT). Cyclic BGPs and the no-active-graph case fall back to stock ARQ (`super.execute`). Counters `invocations()`, `analyses()`, `narrowedBgps()`, `collapsedBgps()` for tests. |
| `AlgebraContextAnalyzer` | `AlgebraContextAnalyzer.java` | Read-only, single-pass walk of an ARQ `Op` tree computing, per `OpBGP` (keyed by object identity), (a) the output variables `O` the rest of the plan needs from that BGP — demand flows top-down: projection resets it; FILTER/ORDER BY/GROUP BY/aggregate/BIND expressions and all sibling operands of join-like operators extend it — and (b) `countsCollapsible`: true below an `OpDistinct` until a multiplicity-sensitive operator (`OpGroup`, `OpSlice`, `OpTopN`, `OpExtend`/`OpAssign`/`OpUnfold`, any fallback) intervenes, and true for the right operand of MINUS / semijoin / anti-join. Falls back to all variables and not-collapsible, with a counted `FallbackReason`, under `OpExt`/`OpService`/`OpPropFunc`/`OpProcedure`. Stores its `Analysis` in the execution `Context` under `AlgebraContextAnalyzer.SYMBOL`. Never modifies the tree. |
| `QueryHypergraph` | `QueryHypergraph.java` | Builds a hypergraph view of a `BasicPattern` — variables as vertices, triple patterns as hyperedges (`Hyperedge` inner class, id = position in the BGP) — with both edge→vars and var→edges incidence maps; identifies join variables. |
| `GyoReduction` | `GyoReduction.java` | GYO ear removal. `reduce(Map<Integer,Set<Var>>)` is the core over abstract edges (shared with the classifier for H+ and the connex projection); `decompose(QueryHypergraph)` wraps it into a `JoinTree`; `isAcyclic` is the executor's pre-check. `gyoRunCount()` is test-only instrumentation counting `reduce` runs. |
| `JoinTree` | `JoinTree.java` | Rooted tree of hyperedges (one node per triple pattern). `build(h, rootId, parentOf)` constructs it from a child→parent map (used by GYO and by the classifier's re-rooted / free-connex trees); `satisfiesRunningIntersection()` is the connectedness self-check used as a correctness oracle in tests; `pretty()` prints it. |
| `QueryClassifier` | `QueryClassifier.java` | Classifies an acyclic BGP against `O` (Wang et al. Def. 3.10 / Bagan–Durand–Grandjean): relation-dominated (one hyperedge ⊇ O; root = that edge), free-connex (H and H+ = H ∪ {O} acyclic; root = root of the free-connex tree, whose connex subtree Tn is exposed), or general acyclic (GYO root, Tn = all nodes). `satisfiesConnexProperty` is the test oracle for Tn. `classify` returns empty for cyclic BGPs. |
| `Relation` | `Relation.java` | Immutable bag of tuples: distinct `Var → Node` rows each annotated with a positive count — the counting semiring (ℕ, +, ×). `project` sums collapsed rows, `join` (hash join on shared variables, cross product if none) multiplies, `semijoin` keeps the left count, graph matches start at 1, `distinct()` resets every count to 1. `rowCount()` counts distinct rows, `bagSize()` sums the counts. |
| `YannakakisPlusEvaluator` | `YannakakisPlusEvaluator.java` | Wang et al. Algorithm 1 (`firstRound`: post-order absorb-or-semijoin with early projection onto O ∪ join variables, returning the reduced tree and semijoin/absorption counts; `evaluateRelationDominated` asserts Theorem 3.11) and Algorithm 2 (`secondRound`: merge children into the dangling-free root, dropping non-output join variables no remaining neighbour still uses; `setInvariantChecks` turns the free-connex "never a general merge" claim into an exception). `evaluate(Classification, rels)` combines them and returns π_O with multiplicities plus counters. |
| `YannakakisEvaluator` | `YannakakisEvaluator.java` | The classical three-pass Yannakakis (upward semijoin → downward semijoin → upward join) returning the full join at the root. Untouched since step 4; no longer on the executor's path. Kept as the differential oracle for the Plus evaluator (π_O of its result is the bag-exact expected answer). |
| `SmokeTest` | `SmokeTest.java` | Standalone `main()` toolchain check: builds a tiny in-memory model, runs a one-triple SPARQL query via stock Jena, prints the result. Not part of the tested surface. |
| `AlgebraExplorer` | `AlgebraExplorer.java` | Demo `main()`: compiles a SPARQL query to the ARQ `Op` algebra tree, prints it, and walks each `OpBGP` describing every triple-pattern node's kind. Not part of the tested surface. |
| `HypergraphDemo` | `HypergraphDemo.java` | Demo `main()`: compiles a fixed SPARQL query and prints the `QueryHypergraph` built from its BGP. Not part of the tested surface. |
| `GyoDemo` | `GyoDemo.java` | Demo `main()`: runs GYO decomposition over path/star/triangle example queries, printing the join tree or reporting cyclicity. Not part of the tested surface. |
| `YannakakisIntuitionDemo` | `YannakakisIntuitionDemo.java` | Demo `main()`: replicates the classical evaluator's semijoin/join passes step by step on hand-built relations, printing intermediate sizes to illustrate dangling-tuple pruning. Not part of the tested surface. |
| `YannakakisArqDemo` | `YannakakisArqDemo.java` | Demo `main()`: runs the same query with stock ARQ and with `YannakakisOpExecutor` registered against the same model, comparing results and printing whether the executor fired. Not part of the tested surface. |

All paths are relative to `src/main/java/at/ac/tuwien/thesis/yannakakis/`.

## 2. Data flow: `OpBGP` → result bindings

Traced through `YannakakisOpExecutor`.

1. **Registration.** `register()` saves the previously installed factory and calls
   `QC.setFactory(ARQ.getContext(), FACTORY)`, where `FACTORY = YannakakisOpExecutor::new`. From
   then on ARQ's main engine constructs a `YannakakisOpExecutor` per query execution and
   dispatches every `OpBGP` node to it.

2. **Algebra analysis, once per execution.** ARQ enters an executor through the protected
   recursive step `exec(Op, QueryIterator)` (`QC.execute` → static `OpExecutor.execute` → `exec`),
   *not* through `executeOp`. The `exec` override runs `AlgebraContextAnalyzer.analyze(op,
   execCxt)` if the execution's `Context` holds no `Analysis` yet — for the first call of an
   execution `op` is the root of the optimised plan — and stores the table under
   `AlgebraContextAnalyzer.SYMBOL`. Later `exec` calls (the recursion within this executor, and the
   fresh executors ARQ creates per row for index joins / EXISTS) share the per-execution `Context`
   and skip it. An analysis that throws is logged and replaced by `emptyAnalysis()` so every lookup
   falls back and nothing is retried (`YannakakisOpExecutorTest#failingAnalysisNeverFailsTheQuery`).

3. **Interception + acyclicity check.** `execute(OpBGP, QueryIterator)` tests
   `activeGraph != null && GyoReduction.isAcyclic(QueryHypergraph.fromBasicPattern(pattern))`. If
   false it returns `super.execute(opBGP, input)` — stock ARQ's nested-loop join from here on.
   Otherwise it bumps `invocations()`, looks up `O` via `AlgebraContextAnalyzer.outputVarsOrAll`
   (all variables if this exact `OpBGP` object was never analysed — ARQ manufactures fresh ones
   for quad patterns and for the `Substitute`d right side of `QueryIterOptionalIndex`) and the
   collapsible flag via `countsCollapsible` (false when unanalysed), and returns a `Stage`
   carrying both.

4. **Per-input-binding evaluation.** `Stage extends QueryIterRepeatApply`, so
   `nextStage(Binding)` runs once per binding arriving from the outer plan:
   - **Substitute**: every bound variable of the pattern is replaced by its node
     (`substitute`/`sub`), producing `bound`.
   - **Classify (cached per binding shape)**: `O` is restricted to the variables still free in
     `bound` (a variable the incoming binding already bound is a constant now and is carried by
     the parent binding) and `QueryClassifier.classify(hypergraph(bound), restricted)` yields the
     kind, the rooted `JoinTree` and the effective `O`. The result depends only on the shape
     (which positions are variables and which), so it is memoised in `planCache` keyed by
     `shapeKey(bound)` for the lifetime of the `Stage`
     (`YannakakisOpExecutorTest#joinTreeCacheAvoidsRedecomposePerBindingShape`).
   - **Materialize**: `matchTriple` runs one `Graph.find` per triple with `Node.ANY` in variable
     positions; `bindPos` makes repeated variables within one triple agree. Each match becomes a
     row with count 1 in a `Relation` keyed by the triple's position.
   - **Evaluate**: `YannakakisPlusEvaluator.evaluate(classification, rels).relation()` — π_O of
     the BGP with multiplicities (see section 2a). The classification is never empty here: binding
     variables to constants only deletes hypergraph vertices, so an acyclic BGP stays acyclic
     (`GyoReductionTest#acyclicityPreservedUnderBinding`).
   - **DISTINCT**: iff the BGP is collapsible, `result = result.distinct()`. This is the only place
     multiplicities are dropped.
   - **Merge back into bindings**: each distinct row is layered onto the incoming `binding` via
     `BindingFactory.builder(binding)` and added `count` times to the output list, wrapped by
     `QueryIterPlainWrapper.create(out.iterator(), getExecContext())`. `QueryIterRepeatApply`
     concatenates these across incoming bindings; projection, ORDER BY, GROUP BY, the actual
     `DISTINCT` operator etc. are still applied by stock ARQ above the BGP — they simply receive
     the π_O bag instead of the full BGP bag.

### 2a. Inside `YannakakisPlusEvaluator.evaluate`

- **Relation-dominated** (O ⊆ A_root): `evaluateRelationDominated` runs the first round and asserts
  that exactly one node remains and no semijoin ran (Theorem 3.11); the answer is π_O of the root.
- **Otherwise**: `firstRound` then `secondRound(reduced, expectReducible = free-connex)`.
- **First round** (Algorithm 1), nodes in post-order, root last: a node whose remaining children
  were all absorbed counts as a leaf; a leaf with A_i ∩ O ⊆ A_p is absorbed —
  `Rp := Rp ⋈ π_{A_p}(R_i)`, which with counts sums the leaf's non-output multiplicities into
  the parent — otherwise `R_i := π_{O ∪ A_i^-}(R_i)`, `Rp := Rp ⋉ R_i`. A_i^- is computed over
  the whole query. The root is projected last. Returns the reduced tree (remaining nodes keep
  their original parent links; they form a connected subtree containing the root) and counters.
- **Second round** (Algorithm 2): the dangling-free node is always the blob the root has grown
  into. A child R_j is *reducible* iff no other neighbour of R_i or of R_j still uses a variable
  of D = (A_i ∩ A_j) \ O; then `R_i := π_{(A_i ∪ A_j) \ D}(R_i ⋈ R_j)` and R_j's children become
  R_i's. Without a reducible child, the general merge joins a child and keeps the variables of D a
  neighbour still needs (`generalMerges`). Finally π_O.
- The semiring makes this bag-exact: `RelationTest#projectionCommutesWithJoinOnTheBag` is the
  property, `YannakakisPlusEvaluatorTest` diffs against π_O of `YannakakisEvaluator`.

## 3. Deviations from Wang et al.

- **Hyperedge rank capped at 3.** `CLAUDE.md` hard rule. Structurally enforced by
  `QueryHypergraph.fromBasicPattern`, which only inspects subject, predicate and object — a
  hyperedge carries at most 3 variables, unlike the paper's arbitrary-arity relations. One
  consequence: a query is relation-dominated only if |O| ≤ 3 (`QueryClassifierTest`).
- **PK-FK-based rules omitted.** `CLAUDE.md` hard rule: cycle elimination, aggregation
  elimination and semijoin elimination (Wang et al. §5.1) rely on key constraints RDF does not
  have. Nothing in the pipeline detects or exploits them. Dimension fusion (cardinality-based)
  is in scope but not implemented yet (step 11).
- **Two-sided reducibility in Algorithm 2.** The one-sided condition ("for every other neighbour
  R_k of R_i, A_k ∩ A_i ⊆ O") ignores R_j's neighbours; on the star {x,a},{x,b},{x,c} with
  O = {a,b,c} it lets the merge of two arms drop x while the third arm still joins on it. The
  implementation checks the neighbours of both merged nodes. On free-connex trees both
  conditions coincide (adjacent Tn nodes share only output variables, D = ∅).
- **General merge instead of a semijoin-only else branch.** Reducibility is a property of the
  variable sets, not of the data, so `R_j := R_j ⋉ R_i` never creates a reducible pair and a
  semijoin-only else branch cannot terminate on the hidden-centre star. The implemented else
  branch joins a child keeping the still-needed variables: always correct, never output-bounded
  — exactly the non-free-connex case, where the paper's guarantee does not apply either.
- **O is an over-approximation.** Sibling demand uses `OpVars.mentionedVars`, which ignores
  scoping; unanalysed `OpBGP` objects (quad patterns, substituted OPTIONAL bodies) get all
  variables and are never collapsible. Safe, not tight.
- **`OpReduced` is not a DISTINCT.** REDUCED permits dropping duplicates, but stock ARQ keeps
  some, and the differential tests compare bags against stock ARQ.
- **No cost-based join tree selection.** `GyoReduction.findEar` picks the first ear in list order
  and its first valid witness; the classifier only re-roots for output-sensitivity. Isolated
  components attach to whichever remaining edge comes first, as a cross product.
- **Execution-time interception only, never algebra rewriting.** `CLAUDE.md` hard rule: ARQ has
  no semijoin operator. The `Transform`-based scaffold was deleted (step 3); the analyzer only
  reads the tree.
- **Cyclic BGPs fall back entirely to stock ARQ.** No generalized hypertree decomposition;
  `DifferentialTest.CyclicDelegation` asserts `invocations() == 0` even when only part of the BGP
  is cyclic.
- **No OPTIONAL support inside the evaluator.** ARQ's own left-join iterator implements
  OPTIONAL around the BGP; the BGP inside still takes the Yannakakis+ path (the substituted body
  is an unanalysed `OpBGP`, hence O = all variables).
- **No RDF-specific match optimizations.** One generic `Graph.find` per triple pattern; no
  predicate statistics or index selection beyond what Jena's `Graph.find` provides.
