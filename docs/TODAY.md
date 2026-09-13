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
  step 3's `mvn test -Dtest=DifferentialTest`.- Step 5 (`AlgebraContextAnalyzer`): `docs/recovery-audit.md` confirms no prior version ever
  existed in history, so this is a fresh build, not a restore. Implemented as a top-down
  "demand" walk implementing `OpVisitor` directly (not `OpVisitorBase`) so every operator must
  be handled explicitly and a future ARQ operator breaks compilation instead of silently
  skipping a BGP. `OpProject` and `OpGroup` are the only operators that *reset* the demand
  (they genuinely hide everything else under SPARQL semantics); every other rule only adds.
  Sibling variables use `OpVars.mentionedVars`, which ignores scoping and therefore can only
  over-approximate. `OpMinus`/`OpSemiJoin`/`OpAntiJoin` right operands get exactly the left
  side's mentioned variables (they never contribute output). Fallback to all variables (with a
  counted `FallbackReason`) for `OpExt`, `OpService`, `OpPropFunc`, `OpProcedure`. Lookup of a
  BGP object that was never analysed also yields all variables — this matters because ARQ
  manufactures fresh `OpBGP` objects at execution time (quad patterns in `OpExecutor`, and the
  `Substitute`d right side of `QueryIterOptionalIndex`), so identity keying will miss those.
  Expected sets in the tests were pinned against `Algebra.compile` output printed for each
  query beforehand. Not wired into `YannakakisOpExecutor` yet (no executor code touched).
  `mvn test`: 68/68 green.
- Step 6 (wire `AlgebraContextAnalyzer` into `YannakakisOpExecutor`): the hook is an override of
  the protected recursive step `exec(Op, QueryIterator)`, not `executeOp` — ARQ's static
  `OpExecutor.execute` calls `exec` directly, so an `executeOp` override is never reached (found
  by a failing `analyses()==1` assertion). "Once per execution" = "no table in the execution's
  `Context` yet"; safe because `QueryExecDatasetBuilder` builds a fresh `Context` per execution
  (it stamps `sysCurrentQuery` on it) and all nested executors share it. `execute(OpBGP)` looks
  up `O` via `outputVarsOrAll` and `Stage` emits only those columns, after the full BGP result is
  materialised and without dedup — multiplicities therefore match ARQ's later projection, so
  this is not the early projection CLAUDE.md defers to step 10. Verified `ExprVars.varsMentioned`
  includes the visible vars of `EXISTS`/`NOT EXISTS` patterns (via `ExprVarsWorker`), so a BGP
  whose variable is only needed by an EXISTS keeps it. New counters `analyses()` and
  `outputProjections()`; `DifferentialTest.OutputProjection` (13 cases: non-DISTINCT duplicates,
  FILTER, EXISTS, NOT EXISTS, ORDER BY, GROUP BY/COUNT, BIND, OPTIONAL, group join, MINUS, UNION,
  subquery aggregate, VALUES — each on a dropped variable) plus a projected variant of the
  randomized suite (random proper subset of variables, non-DISTINCT, 30 rounds, seeds
  20260706+1000..). `mvn test`: 84/84 green.
- Step 6, correction against the wiring checklist: the previous entry's "Stage emits only the O
  columns" overstepped the checklist item "do not use O to change any behaviour yet". Reverted:
  `Stage` now emits all variables again; `O` is looked up, restricted per binding shape to the
  variables still free after substitution (cached in a `ShapePlan` next to the join tree),
  logged at debug, and otherwise unused. `outputProjections()` renamed `narrowedBgps()` (counts
  BGPs whose looked-up `O` is a strict subset — an analyzer metric, not a projection). The
  analysis call in `exec` is now guarded: a throwing analyzer is logged and replaced by
  `AlgebraContextAnalyzer.emptyAnalysis()` so the query proceeds with all variables and the
  failure is not retried per nested call (`YannakakisOpExecutorTest#failingAnalysisNeverFailsTheQuery`,
  built by hand from an `OpExt` whose `effectiveOp()` throws, run via `QC.execute` with
  `ExecutionContext.setExecutor(FACTORY)`). The 13 dropped-variable differential cases moved out
  of `DifferentialTest` into the new `OutputVariableSafetyTest`: a flat 62-query corpus (every
  query from `DifferentialTest`, `YannakakisOpExecutorTest`, `EngineComparisonTest`,
  `Tdb2AuditScratchTest`, `AlgebraContextAnalyzerTest`, exact duplicates collapsed, plus the 13)
  run as one `@ParameterizedTest` over a single superset `Dataset` (default graph + named graph
  `g1` so the `GRAPH` queries are non-trivial); canonical comparison, ORDER BY queries compared
  ordered, the bare-LIMIT query by row count. Omitted the `SERVICE` query (remote call); the two
  analyzer ORDER BY queries got a tie-breaking `?x` so both engines' order is well-defined.
  `mvn test`: 136/136 green.
- Step 7 (`QueryClassifier`: relation-dominated / free-connex / acyclic w.r.t. O): new class, GYO
  left untouched except for exposing its ear-removal core over abstract `id → vars` maps
  (`GyoReduction.reduce`, package-private) so H+ = H ∪ {[O]} and the connex projection can run
  through the very same code without synthetic `Hyperedge`s; the tree-building tail of
  `decompose` moved to `JoinTree.build` for the same reason. Free-connex tree construction: root
  the H+ tree at [O], delete it, its children C1..Ck are Tn; a second GYO on the projections
  Ei = vars(Ci) ∩ O gives Tn's shape (acyclic because it is the vertex restriction of the acyclic
  H to O minus subset edges — every O-variable in a subtree under [O] is already in that
  subtree's top node by running intersection), each Ci keeps its subtree. Naively hanging all
  Ci under one of them is wrong (found by hand: C1={a}, C2={b,c}, C3={b,c'} with O={a,b,c}
  breaks running intersection for b), hence the second GYO. Precedence relation-dominated >
  free-connex > acyclic; the classes are nested (O ⊆ e makes [O] an ear under e), and the
  randomized test asserts that. `Classification.connexSubtree()` is Tn for the two free-connex
  classes ({root} when relation-dominated) and *all* nodes for a general acyclic query, so the
  evaluator can uniformly "reduce, join Tn, project". O is intersected with the BGP's variables
  (the analyzer already guarantees that; the classifier is just total). `|O| ≥ 4` is asserted
  never relation-dominated, in fixed cases and across 200 random rounds (seed 20260913). Not
  wired into `YannakakisOpExecutor` yet. `mvn test`: 159/159 green.
- Step 9 (`YannakakisPlusEvaluator`: Wang et al. Algorithm 1, first round): new class,
  `YannakakisEvaluator` untouched and used as the differential oracle (π_O of its full join).
  `firstRound(tree, O, rels)` follows the pseudo-code literally over the tree rooted by the
  classifier: "leaf" is tested on the shrinking tree (a node whose children were all absorbed is
  a leaf when its turn comes), A_i^- is computed over the whole query (a var in ≥ 2 hyperedges),
  the absorption `Rp ⋈ π_{A_p}(R_i)` is performed as a join and counted as an absorption even
  though under set semantics it equals a semijoin, because the Chapter 3 claim counts semijoins.
  Returns a `Reduced` record: the reduced `JoinTree` (fresh nodes over the surviving
  hyperedges with their original parent links — valid because an absorbed node was a leaf, so
  what remains is a connected subtree containing the root), surviving relations, and the
  semijoin/absorption counters. `Relation.project(Set<Var>)` added (schema ∩ vars, set-based).
  Theorem 3.11 short-circuit: `evaluateRelationDominated` requires O ⊆ A_root (throws otherwise)
  and asserts one node + zero semijoins after the round; `evaluate(Classification, rels)` uses
  it for `RELATION_DOMINATED` and otherwise finishes with `Reduced.answer()`, a plain join of
  the reduced tree plus π_O — the correctness finish needed to diff against the oracle, not the
  paper's second round. The executor still runs the classical evaluator: projecting onto O
  early collapses duplicates that non-DISTINCT queries keep, so wiring waits for step 10's
  multiplicities. Hence "OutputVariableSafetyTest against the Plus evaluator" is done at the
  BGP level (`YannakakisPlusEvaluatorTest`): every corpus query compiled, O per BGP from
  `AlgebraContextAnalyzer.analyze(op)` exactly as the executor looks it up, relations matched
  against the corpus graph, cyclic BGPs skipped (the executor delegates them), then Plus vs
  oracle on the classified tree, with the theorem asserted on every relation-dominated BGP
  (≥ 10 in the corpus). Fixed cases pin the reduced tree (1 node / 0 semijoins / n−1
  absorptions for dominated; 3 nodes / 2 semijoins for the path with O = endpoints), plus a
  200-round random suite (same shape/O draws and seed as `QueryClassifierTest`, random 0–5-row
  relations over four constants) covering all three classes. `mvn test`: 234/234 green.
- Step 10 (multiplicities, the counting semiring) and Algorithm 2 (second round), wired into the
  executor. `Relation` now stores distinct tuple → positive count: `project` sums collapsed rows,
  `join` multiplies, `semijoin` keeps the left count, graph matches start at 1, `distinct()` sets
  every count to 1. That makes round one's absorption `Rp ⋈ π_{A_p}(R_i)` bag-exact for free
  (the multiplicities of the absorbed leaf's non-output columns are summed into the parent) and
  is why the paper writes it as a join, not a semijoin. The DISTINCT switch is a per-BGP flag
  computed by `AlgebraContextAnalyzer` (`Entry.countsCollapsible`): true below an `OpDistinct`
  until a multiplicity-sensitive operator intervenes — `OpGroup` (aggregates count duplicates),
  `OpSlice`/`OpTopN` (LIMIT over a bag), `OpExtend`/`OpAssign`/`OpUnfold` (RAND/UUID/BNODE would
  give duplicates different values), any fallback operator — and also true for the right operand
  of MINUS / semijoin / anti-join, where only existence matters; `OpReduced` is deliberately not a
  DISTINCT (stock ARQ keeps some duplicates under REDUCED and the differential tests would see
  it). The algorithms never look at the flag; `Stage.nextStage` applies `distinct()` at the very
  end and otherwise emits each row `count` times. The executor now runs the full pipeline:
  classify per binding shape (cached), `YannakakisPlusEvaluator.evaluate`, emit π_O — the
  advisory-only O of step 6b is consumed from here on. `GyoReduction`'s test counter moved from
  `decompose` to `reduce` (`gyoRunCount`) because `classify` bypasses `decompose`; the cache
  test's arithmetic (growth 4 vs 6) is unchanged since a bound single-edge pattern costs one run.
  Algorithm 2: the dangling-free node is always the blob the root has grown into (a join of a
  dangling-free relation with an upward-reduced child stays dangling-free), a child R_j is
  reducible iff no other neighbour of R_i *or of R_j* still uses a variable of D = (A_i ∩ A_j) \ O
  — the one-sided condition "A_k ∩ A_i ⊆ O for R_i's other neighbours" is not enough: on the star
  {x,a},{x,b},{x,c} with O = {a,b,c} it lets the merge of two arms drop x while the third arm
  still joins on it. The literal else branch (`R_j := R_j ⋉ R_i` only) cannot terminate there
  either: reducibility is a property of the variable sets, not the data, so no semijoin ever
  creates a reducible pair and the loop would spin. Implemented else branch: join R_i with a child
  and keep the dropped variables some remaining neighbour still needs (`generalMerges` counter);
  correct always, output-bounded never — it is exactly the non-free-connex case. The free-connex
  invariant (else never taken, because the remaining tree is Tn whose adjacent nodes share only
  output variables, so D = ∅) is asserted by `setInvariantChecks(true)` and counted otherwise;
  `evaluate(Classification)` passes `expectReducible = (kind == FREE_CONNEX)`. Tests:
  `RelationTest` (semiring rules, π/⋈ commutation on the bag), `YannakakisPlusEvaluatorTest`
  (bag-exact oracle = π_O of the classical evaluator; hidden-centre star reaches exactly one
  general merge and throws when declared free-connex with checks on; fan-out path keeps
  {a=1,d=4}×2; corpus and 200 random rounds with checks on, `generalMerges == 0` on every
  free-connex BGP), `OutputVariableSafetyTest` runs the 62-query corpus plus the DISTINCT variant
  of every non-DISTINCT query (`Query.setDistinct(true)`, 59 more) end to end through the executor
  against stock ARQ, bag-exact (sorted row lists keep duplicates), and asserts `collapsedBgps()`
  ≥ 20 on the DISTINCT variants but ≤ 5 without DISTINCT and strictly fewer than the invocations
  (GROUP BY / LIMIT / BIND below the DISTINCT keep their counts). `mvn test`: 302/302 green.
- Step 10, docs follow-up: CLAUDE.md's pipeline/integration/testing sections and the bag-semantics
  hard rule rewritten for the current tree (classifier, Plus evaluator, counting semiring, single
  DISTINCT entry point, `OpReduced` not a DISTINCT); the Jena API list gained the methods verified
  today (`Query.setDistinct`, `OpWalker`/`OpVisitorBase`, `Var.getVarName`).
  `docs/implementation-inventory.md` rewritten as a step-10 snapshot: classes table, data flow
  citing methods instead of line numbers (the old ones went stale within a day), and the
  deviations list — set-semantics entry replaced by the two Algorithm 2 deviations (two-sided
  reducibility, general merge), the O over-approximation and the `OpReduced` decision; the
  "no join-tree caching" and `naiveFold` entries dropped because neither exists any more.
