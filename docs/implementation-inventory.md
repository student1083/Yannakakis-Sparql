 # Implementation inventory

Snapshot of `at.ac.tuwien.thesis.yannakakis` as of the deletion of the abandoned
`YannakakisQueryEngine`/`YannakakisTransform` scaffold (see `docs/today.md`, step 3).
Written to support Chapter 4: every class's responsibility, the data flow from `OpBGP`
to result bindings, and every deviation from Wang et al.'s Yannakakis algorithm, with
the reason for each.

Note: `CLAUDE.md`'s architecture section still describes `YannakakisQueryEngine` and
`YannakakisTransform` as present-but-abandoned. They have since been deleted outright
(zero inbound references, `transform(OpBGP)` was a permanent no-op, and the whole
approach contradicted the execution-time-only integration rule below) — this document
reflects the current source tree, not that stale description.

## 1. Classes

| Class | Path | Responsibility |
|---|---|---|
| `YannakakisOpExecutor` | `YannakakisOpExecutor.java` | The live ARQ integration point: an `OpExecutor` registered via `QC.setFactory` that intercepts `OpBGP` execution, runs the GYO → `JoinTree` → `Relation` → `YannakakisEvaluator` pipeline for acyclic BGPs against the active graph, and falls back to stock ARQ (`super.execute`) for cyclic BGPs or when there is no active graph. |
| `QueryHypergraph` | `QueryHypergraph.java` | Builds a hypergraph view of a `BasicPattern` — variables as vertices, triple patterns as hyperedges (`Hyperedge` inner class) — with both edge→vars and var→edges incidence maps; identifies join variables. |
| `GyoReduction` | `GyoReduction.java` | Runs the GYO ear-removal algorithm to test α-acyclicity of a `QueryHypergraph` and, if acyclic, builds the corresponding `JoinTree`. |
| `JoinTree` | `JoinTree.java` | Rooted tree of hyperedges (one node per triple pattern) produced by `GyoReduction`; exposes `satisfiesRunningIntersection()` as a connectedness self-check used as a correctness oracle in tests, and a `pretty()` printer. |
| `Relation` | `Relation.java` | Immutable set-of-tuples (`Var → Node` rows) representing a materialized triple pattern or intermediate result; provides `semijoin`, `join` (hash join on shared variables, cross product if none) and builder/factory methods. |
| `YannakakisEvaluator` | `YannakakisEvaluator.java` | Given a `JoinTree` and one `Relation` per edge, runs upward semijoin → downward semijoin → upward join passes and returns the joined `Relation` at the root. |
| `AlgebraContextAnalyzer` | `AlgebraContextAnalyzer.java` | Read-only, single-pass walk of an ARQ `Op` tree computing, per `OpBGP` (keyed by object identity), the output variables `O` the rest of the plan needs from that BGP (demand flows top-down: projection resets it; FILTER/ORDER BY/GROUP BY/aggregate/BIND expressions and all sibling operands of join-like operators extend it). Falls back to all variables, with a counted `FallbackReason`, under `OpExt`/`OpService`/`OpPropFunc`/`OpProcedure`. Stores its `Analysis` in the execution `Context` under `AlgebraContextAnalyzer.SYMBOL`. Never modifies the tree; not yet consulted by `YannakakisOpExecutor`. |
| `SmokeTest` | `SmokeTest.java` | Standalone `main()` toolchain check: builds a tiny in-memory model, runs a one-triple SPARQL query via stock Jena, prints the result. Not part of the tested surface. |
| `AlgebraExplorer` | `AlgebraExplorer.java` | Demo `main()`: compiles a SPARQL query to the ARQ `Op` algebra tree, prints it, and walks each `OpBGP` describing every triple-pattern node's kind (variable/IRI/literal/blank). Not part of the tested surface. |
| `HypergraphDemo` | `HypergraphDemo.java` | Demo `main()`: compiles a fixed SPARQL query and prints the `QueryHypergraph` built from its BGP. Not part of the tested surface. |
| `GyoDemo` | `GyoDemo.java` | Demo `main()`: runs GYO decomposition over path/star/triangle example queries, printing the resulting join tree or reporting cyclicity. Not part of the tested surface. |
| `YannakakisIntuitionDemo` | `YannakakisIntuitionDemo.java` | Demo `main()`: manually replicates the evaluator's semijoin/join passes step-by-step on hand-built relations, printing intermediate sizes to illustrate the dangling-tuple pruning intuition. Not part of the tested surface. |
| `YannakakisArqDemo` | `YannakakisArqDemo.java` | Demo `main()`: runs the same query with stock ARQ and with `YannakakisOpExecutor` registered against the same model, comparing results and printing whether the executor fired. Not part of the tested surface. |

All paths are relative to `src/main/java/at/ac/tuwien/thesis/yannakakis/`.

## 2. Data flow: `OpBGP` → result bindings

Traced through `YannakakisOpExecutor.java`.

1. **Registration.** `register()` (`:66-70`) saves the previously-installed factory and calls
   `QC.setFactory(ARQ.getContext(), FACTORY)`, where `FACTORY = YannakakisOpExecutor::new`
   (`:40`). From then on, ARQ's main execution engine constructs a `YannakakisOpExecutor` per
   query execution and dispatches every `OpBGP` node to it.

2. **Interception + acyclicity check.** `execute(OpBGP opBGP, QueryIterator input)` (`:49-62`)
   pulls the pattern via `opBGP.getPattern()` (`:51`) and the active graph via
   `execCxt.getActiveGraph()` (`:52`). It tests acyclicity at `:54-55`:
   `activeGraph != null && GyoReduction.isAcyclic(QueryHypergraph.fromBasicPattern(pattern))`.
   If false (cyclic BGP, or no active graph), it delegates unchanged: `return super.execute(opBGP,
   input)` (`:58`) — stock ARQ's default nested-loop join handles it from here on. Otherwise the
   invocation counter is bumped (`:60`, read by tests via `invocations()`/`resetCounter()`,
   `:77-78`) and a `Stage` is returned (`:61`).

3. **Per-input-binding evaluation.** `Stage` (`:82-119`) extends `QueryIterRepeatApply`, so
   `nextStage(Binding binding)` (`:90-118`) runs once per binding arriving from the outer query
   plan (a BGP can be nested under joins/OPTIONAL/subqueries that already bound some variables):
   - **Substitute** (`:93-94`): each `Triple` in the original pattern has its bound variables
     replaced with concrete `Node`s via `substitute`/`sub` (`:123-133`), producing `bound`, a
     `BasicPattern` with fewer free variables.
   - **Rebuild hypergraph + join tree** (`:97-98`): `GyoReduction.decompose(QueryHypergraph
     .fromBasicPattern(bound))` runs again on the *substituted* pattern — not reusing the
     acyclicity check from step 2 — returning `Optional<JoinTree>`.
   - **Materialize against the live graph** (`:100-104`, via `matchTriple`, `:135-157`): for each
     triple in `bound`, `g.find(match(s), match(p), match(o))` queries the graph with
     `Node.ANY` wildcards for variable positions (`match`, `:159`); matching triples become rows
     of a `Relation` keyed by the triple's position `id` in `rels`. `bindPos` (`:164-170`) checks
     that repeated variables within one triple agree on the same value.
   - **Evaluate** (`:107-108`): `jt.map(tree -> YannakakisEvaluator.evaluate(tree,
     rels)).orElseGet(() -> naiveFold(rels))` — Yannakakis pipeline if the substituted pattern is
     acyclic, else `naiveFold` (`:172-176`, plain left-to-right natural join) as a safety net.
   - **Merge back into bindings** (`:110-117`): each row of the resulting `Relation` is layered
     onto the *original* incoming `binding` via `BindingFactory.builder(binding)` (`:113`),
     producing one `Binding` per result row, wrapped as a `QueryIterator` via
     `QueryIterPlainWrapper.create(out.iterator(), getExecContext())` (`:117`).
     `QueryIterRepeatApply` concatenates these across all incoming bindings and hands the result
     back up the ARQ iterator chain, where projection, `DISTINCT`, `ORDER BY`, etc. are all
     applied by stock ARQ around the BGP.

4. **Inside `YannakakisEvaluator.evaluate(JoinTree, Map<Integer,Relation>)`** (`:18-58`):
   - Empty BGP short-circuit: `tree.root() == null` → `Relation.unit()` (`:19`).
   - Builds post-order and pre-order node lists (`postOrder`/`preOrder`, `:60-67`).
   - **Phase 1 — upward semijoin** (`:34-41`): for each node in post-order (children before
     parents), `parent := parent ⋉ child` via `Relation.semijoin` (`Relation.java:68-82`).
   - **Phase 2 — downward semijoin** (`:42-48`): for each node in pre-order (parents before
     children), `child := child ⋉ parent` — together with phase 1, the full semijoin reducer.
   - **Phase 3 — upward join** (`:49-56`): for each node in post-order again, `parent := parent ⋈
     child` via `Relation.join` (`Relation.java:85-116`, hash join on shared variables or cross
     product if none shared), accumulating the answer at the root.
   - Returns `rel.get(tree.root().edge().id())` (`:57`) — handed back to `Stage.nextStage`.

5. **Supporting structures:**
   - `QueryHypergraph.fromBasicPattern` (`QueryHypergraph.java:61-79`) builds one `Hyperedge` per
     triple (vars collected via `collectVar`, `:81-85`) and the `vertexToEdges` incidence map,
     using `LinkedHashMap`/`LinkedHashSet` for deterministic order.
   - `GyoReduction.decompose` (`GyoReduction.java:31-66`) repeatedly removes "ears" — hyperedges
     whose shared variables are all covered by one other edge (the witness) — via `findEar`
     (`:75-100`), recording child→parent links. An edge with no shared variables (an isolated
     component) attaches to an arbitrary remaining edge as a cross-product join (`:88-91`). If no
     ear exists while more than one edge remains, `decompose` returns `Optional.empty()` (cyclic,
     `:48`). On success, `JoinTree.Node`s are built and wired via `setParent`/`children()`
     (`:55-64`).
   - `JoinTree.satisfiesRunningIntersection()` (`JoinTree.java:54-72`) is a correctness self-check
     (used in tests, not on the hot path): for every variable, the nodes containing it must form
     exactly one connected entry point into the tree.

## 3. Deviations from Wang et al. / the general Yannakakis algorithm

- **Set semantics only, no multiset/bag join.** `Relation` is explicitly a *set* of tuples
  (`Relation.java:19-23`: "a graph is a set of triples, so a single BGP yields set semantics"),
  backed by `HashSet<Map<Var,Node>>` throughout `semijoin`/`join`. `CLAUDE.md` hard rule: "Scope
  is SELECT DISTINCT only (set semantics). No multiset/bag support." Wang et al.'s Yannakakis+ is
  stated over bags with duplicate counts; this implementation de-duplicates at every `Relation`
  operation and cannot preserve multiplicities.
- **PK-FK and Dimension Fusion rules omitted.** `CLAUDE.md` hard rule: "PK-FK and Dimension
  Fusion rules from Wang et al. do not transfer to RDF. Never implement them." No detection or
  shortcut for such relationships exists in `YannakakisEvaluator`, `GyoReduction`, or `Relation`
  — every join tree edge goes through the full three-phase pass unconditionally.
- **Hyperedge rank capped at 3.** `CLAUDE.md` hard rule: "RDF triples limit hyperedge rank
  (arity) to 3." Structurally enforced in `QueryHypergraph.fromBasicPattern` (`:66-77`), which only ever
  inspects `t.getSubject()`, `t.getPredicate()`, `t.getObject()` — a hyperedge (one triple
  pattern) carries at most 3 distinct variables, unlike the paper's arbitrary-arity relational
  hyperedges.
- **No cost-based join tree selection.** `GyoReduction.findEar` (`:75-100`) picks the first ear
  found by iterating `remaining` in list order, and the first valid witness for it — there is no
  choice among candidate ears/witnesses based on cardinality estimates or resulting tree shape.
  `docs/today.md` explicitly lists "Cost-based join tree selection" as out of scope.
- **Isolated components attach arbitrarily, not cost-aware.** When a hyperedge shares no
  variables with the rest of the hypergraph, `findEar` (`:88-91`) attaches it to whichever
  remaining edge comes first in iteration order as a cross-product join, without regard to which
  anchor minimizes the resulting intermediate size.
- **Execution-time interception only, never algebra rewriting — by explicit design rule.**
  `CLAUDE.md` hard rule: "Integration happens at EXECUTION TIME via `OpExecutor` interception.
  Never via algebra rewriting / `Transform` on the algebra tree. ARQ has no native semijoin
  operator, so semijoin passes must run at execution time." The earlier `Transform`-based
  approach (`YannakakisQueryEngine`/`YannakakisTransform`) was built, found to contradict this
  rule (and never actually implemented — `transform(OpBGP)` was a permanent no-op), and deleted.
- **Cyclic BGPs fall back entirely to stock ARQ — no generalized hypertree decomposition.**
  `YannakakisOpExecutor.execute` (`:54-58`) only implements the pure α-acyclic case (GYO ear
  removal); when `GyoReduction.isAcyclic` returns false it hands the *whole* BGP to stock ARQ's
  nested-loop join, with no attempt at bounded generalized-hypertree-width decomposition of a
  partially-cyclic pattern. Confirmed by `DifferentialTest.CyclicDelegation` (`triangle`,
  `fourCycle`, `cyclicCoreWithAcyclicTail`), which assert `invocations() == 0` even when only part
  of the BGP is cyclic.
- **Join tree is rebuilt from scratch on every incoming binding, with no caching.**
  `Stage.nextStage` (`:90-118`) reruns `GyoReduction.decompose` for every binding fed in from the
  outer plan (`:97-98`), even though the join tree's *structure* is invariant across bindings for
  a fixed BGP shape — only the materialized `Relation`s differ. No memoization keyed by BGP shape.
- **No OPTIONAL support inside the evaluator itself.** `docs/today.md` lists "OPTIONAL handling"
  as out of scope for this milestone. `DifferentialTest.Wrappers.optional()` only verifies the BGP
  *inside* an `OPTIONAL` block still fires the Yannakakis path — ARQ's own left-join iterator,
  not `YannakakisEvaluator`, implements the left-outer-join semantics around it.
- **No RDF-specific match optimizations.** `matchTriple` (`:135-157`) always does one generic
  `Graph.find` per triple pattern with `Node.ANY` wildcards; there is no use of predicate
  statistics, index selection, or property-path handling beyond what Jena's default `Graph.find`
  already provides.
- **`naiveFold` is an unordered, non-cost-based safety net.** `naiveFold` (`:172-176`) is only
  reached if the post-substitution BGP unexpectedly turns out cyclic despite the outer acyclicity
  check having passed pre-substitution; it does a plain left-to-right natural join in relation
  iteration order, with no join-order optimization — a correctness fallback, not a performance
  path.
