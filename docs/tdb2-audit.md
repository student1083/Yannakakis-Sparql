# TDB2 integration audit

Empirical audit of how `YannakakisOpExecutor.register()` (global `QC.setFactory(ARQ.getContext(), …)`)
behaves against a TDB2 dataset in Jena 6.0.0, with an in-memory model/dataset as control.
Read-only investigation; **no source changes**. The findings below come from running a scratch
test, cross-checked against the Jena 6.0.0 source jars (`jena-arq-6.0.0-sources.jar`,
`jena-tdb2-6.0.0-sources.jar`).

Date: 2026-09-13. Jena/ARQ version reported at runtime: `6.0.0`.

## Method

Scratch test: `src/test/java/at/ac/tuwien/thesis/yannakakis/Tdb2AuditScratchTest.java`
(may be deleted; it is not part of the correctness suite and asserts nothing).

- Builds a TDB2 store under `target/tdb2-audit/<yyyyMMdd-HHmmss>/store` via
  `TDB2Factory.connectDataset(dir)` and loads 5 triples into the default graph and the same
  5 into named graph `<http://example.org/g1>`. The store directory is deliberately **not**
  deleted (Windows: TDB2 memory-maps its block files; a mapped DB cannot be removed while the
  JVM that opened it is alive).
- Writes a full report to `target/tdb2-audit/<stamp>/report.txt`. The run quoted below is
  `target/tdb2-audit/20260913-113708/report.txt`.
- For every (store, registration mode, query) combination it records:
  1. the `OpExecutorFactory` found in the **merged query context** (`QC.getFactory(qe.getContext())`) —
     this is exactly what `ExecutionContext.create(dsg, cxt)` hands to `OpExecutor.createOpExecutor`;
  2. the concrete class that factory constructs (`factory.create(ExecutionContext.create(dsg, cxt)).getClass()`,
     with `null → OpExecutor.stdFactory`, mirroring `OpExecutor.createOpExecutor`);
  3. `YannakakisOpExecutor.invocations()` after the query (counts calls that took the
     Yannakakis branch of `execute(OpBGP, …)`);
  4. calls reaching the **global stage generator** (the audit wraps
     `StageBuilder.chooseStageGenerator(ARQ.getContext())` with a recorder and restores it in
     `@AfterAll`). Plain `OpExecutor.execute(OpBGP)` is the only route into the stage generator,
     so a recorded call is positive evidence that `super.execute(OpBGP, …)` ran;
  5. ARQ's explain log (`ARQ.setExecutionLogging(InfoLevel.ALL)`, captured from stderr).
     Fingerprints: an `ALGEBRA` line = the op actually executed after optimisation/quadification;
     a `TDB2` line = `OpExecutorTDB2.exec` ran at top level; an `Execute` line =
     `OpExecutorTDB2.executeBGP/optimizeExecuteQuads` → `PatternMatchTDB2`; `BGP` +
     `Reorder/generic` lines = stock ARQ `StageGeneratorGeneric` on an in-memory graph.
  6. the canonical result rows.

Registration modes:

| Mode | What is set |
|---|---|
| **NONE** | nothing (baseline) |
| **GLOBAL** | `YannakakisOpExecutor.register()` = `QC.setFactory(ARQ.getContext(), FACTORY)` — the current production mechanism |
| **PER_DATASET** | `QC.setFactory(dsg.getContext(), YannakakisOpExecutor.FACTORY)` on the TDB2 dataset's own context — probe of the proposed fix, restored afterwards |

Queries (all over `PREFIX ex: <http://example.org/>`):

| Label | Pattern |
|---|---|
| `default-bgp` | `?a ex:knows ?b . ?b ex:livesIn ?c` |
| `graph-named` | `GRAPH ex:g1 { same two triples }` |
| `graph-var` | `GRAPH ?g { same two triples }` |
| `filter-last-var` | two triples + `FILTER(?c != ex:graz)` (filter variable bound by the last triple) |
| `filter-first-var` | two triples + `FILTER(?a != ex:c)` (filter variable bound by the first triple) |
| `cyclic-triangle` | `?x ex:knows ?y . ?y ex:knows ?z . ?x ex:knows ?z` (must delegate) |

Environment facts printed at start-up (same JVM, TDB2 already initialised by class loading):

```
TDB2 dataset class: org.apache.jena.tdb2.store.DatasetGraphSwitchable
TDB2 dataset ctx factory: org.apache.jena.tdb2.solver.OpExecutorTDB2$1
mem dataset ctx factory: <null> (-> OpExecutor.stdFactory)
global ARQ ctx factory (before register): <null> (-> OpExecutor.stdFactory)
global ARQ ctx stage generator: org.apache.jena.tdb2.solver.StageGeneratorDirectTDB
global optFilterPlacement: null optFilterPlacementBGP: null
```

Note the last two lines: merely having `jena-tdb2` on the classpath makes `TDB2.init()`
replace the **global** stage generator with `StageGeneratorDirectTDB` (wrapping the original
generic one). This affects the in-memory control too (see Q5).

## Results summary

| Store | Mode | Query | Factory in query ctx | Executor constructed | Yannakakis invocations | Stage-gen calls | `TDB2`/`Execute` explain lines |
|---|---|---|---|---|---|---|---|
| TDB2 | NONE | all 6 | `OpExecutorTDB2$1` | `OpExecutorTDB2` | 0 | none | yes / yes |
| TDB2 | **GLOBAL (register())** | all 6 | `OpExecutorTDB2$1` | `OpExecutorTDB2` | **0** | none | yes / yes |
| TDB2 | PER_DATASET | default-bgp | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | no / no |
| TDB2 | PER_DATASET | graph-named | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | no / no |
| TDB2 | PER_DATASET | graph-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | no / no |
| TDB2 | PER_DATASET | filter-last-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | no / no |
| TDB2 | PER_DATASET | filter-first-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | **2** (two 1-triple BGPs) | none | no / no |
| TDB2 | PER_DATASET | cyclic-triangle | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 0 | **1**: `StageGeneratorDirectTDB size=3 activeGraph=GraphTDB` | no / no |
| mem Dataset | GLOBAL | default-bgp, graph-named, graph-var, filter-last-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | – |
| mem Dataset | GLOBAL | filter-first-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | **2** | none | – |
| mem Dataset | GLOBAL | cyclic-triangle | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 0 | 1: `StageGeneratorDirectTDB size=3 activeGraph=DatasetGraphMap$NamedGraphWrapper` + `BGP`/`Reorder/generic` | – |
| mem Model | GLOBAL | default-bgp, filter-last-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 1 | none | – |
| mem Model | GLOBAL | filter-first-var | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | **2** | none | – |
| mem Model | GLOBAL | cyclic-triangle | `YannakakisOpExecutor.FACTORY` | `YannakakisOpExecutor` | 0 | 1: `StageGeneratorDirectTDB size=3 activeGraph=GraphMemValue` + `BGP`/`Reorder/generic` | – |

Result rows were identical across all modes for every query (2 / 2 / 2 / 1 / 2 / 0 rows).

## Q1 — Whose `OpExecutor` is constructed for a TDB2 dataset after `register()`?

**`OpExecutorTDB2`. Ours is never constructed.**

Evidence (report section A, every query):

```
factory in query ctx   : org.apache.jena.tdb2.solver.OpExecutorTDB2$1
executor constructed   : org.apache.jena.tdb2.solver.OpExecutorTDB2
yannakakis invocations : 0
[main] INFO org.apache.jena.arq.exec - TDB2      <- OpExecutorTDB2.exec() ran
[main] INFO org.apache.jena.arq.exec - Execute   <- OpExecutorTDB2 -> PatternMatchTDB2
```

Section A (register()) is byte-for-byte the same as section B (nothing registered).

Why (source): the query context is built by `Context.setupContextForDataset(global, dsg)` →
`Context.mergeCopy(global, dsg.getContext())` = `global.copy(); putAll(dsgContext)`
(`Context.java:437-483`, used by `QueryExecDataset.java:104` and `QueryEngineBase.java:94`).
`TDB2StorageBuilder.build` (`TDB2StorageBuilder.java:111`) does
`QC.setFactory(dsg.getContext(), OpExecutorTDB2.OpExecFactoryTDB)` on every TDB2 dataset, so
the dataset's entry for `ARQConstants.sysOpExecutorFactory` **overwrites** the global one.
`ExecutionContext.create(dsg, ctx)` then reads `QC.getFactory(ctx)` (`ExecutionContext.java:146-151`)
and `OpExecutor.createOpExecutor` uses that factory (`OpExecutor.java:72-79`).

`TDB2Factory.connectDataset` returns a `DatasetGraphSwitchable`; it shares the *same*
`Context` object as the wrapped `DatasetGraphTDB` (`DatasetGraphSwitchable.java:48`,
`super(null, dsg.getContext())`), so `dsg.getContext()` on the handle you hold is the context
that wins.

**Implication for benchmark validity:** any "Yannakakis vs stock" measurement over TDB2 that
relies on `register()` measures **TDB2 vs TDB2**. `invocations()` stays 0. The only reason the
existing tests pass is that they are all in-memory (`Model` / `DatasetGraphMap`), whose contexts
carry no factory. `DifferentialTest`'s invocation assertions would catch this immediately for
TDB2 — which is why a TDB2 variant of `DifferentialTest` is the first thing to add once a fix
is in.

## Q2 — If ours wins, what does `super.execute(OpBGP, input)` resolve to?

Observed under the PER_DATASET probe (section C, `cyclic-triangle`):

```
executor constructed   : at.ac.tuwien.thesis.yannakakis.YannakakisOpExecutor
yannakakis invocations : 0
stage generator calls  :
    stageGenerator(StageGeneratorDirectTDB) size=3 activeGraph=org.apache.jena.tdb2.store.GraphTDB
explain log            : QUERY, ALGEBRA only  — no "TDB2" line, no "Execute" line
```

So `super.execute(OpBGP, …)` is **plain `OpExecutor.execute(OpBGP)`** (`OpExecutor.java:127-133`),
i.e. `stageGenerator.execute(pattern, input, execCxt)`. Because `TDB2.init()` installed
`StageGeneratorDirectTDB` globally (`TDB2.java:145-155`), the pattern *does* end up in
`PatternMatchTDB2.execute(graph, pattern, …)` (`StageGeneratorDirectTDB.java:48-60`) — TDB2's
NodeId-level index matcher — **but without TDB2's reordering step**.
`OpExecutorTDB2.optimizeExecuteTriples` / `optimizeExecuteQuads` are the only places that call
`dsgtdb.getReorderTransform()` + `reorder(pattern, peek, transform)` (`OpExecutorTDB2.java:194-230,
233-276, 296-316`) and they are bypassed entirely (no `TDB2` / `Execute` explain lines). The
class comment on `StageGeneratorDirectTDB` says exactly this: "Execute TDB requests directly --
no reordering. Using OpExecutor is preferred."

What "reordering" means concretely in this store: the default transform is `ReorderLib.fixed()`
(`SystemTDB.java:186`); it becomes statistics-weighted only if `<store>/Data-0001/stats.opt`
exists (`DatabaseOps.java:601-621`). So the bypassed component is TDB2's fixed-heuristic (or
stats-based, if `stats.opt` is generated) triple reordering, applied per input binding via
`QueryIterPeek`.

**Implication for benchmark validity:** with a plain-`OpExecutor` subclass registered per
dataset, the *cyclic fallback* inside a Yannakakis run would run TDB2 index matching in
**source order**, not in TDB2's chosen order. For cyclic queries the "Yannakakis engine" column
would therefore be a *degraded* TDB2, and the delta vs. stock would be an artefact of the
missing reorder, not of the join algorithm. Delegation must resolve to
`OpExecutorTDB2.execute(OpBGP/OpQuadPattern)` to be a fair "stock" fallback.

## Q3 — Does a TDB2 query reach `execute(OpBGP, …)` at all? (quad rewriting)

`QueryEngineTDB.modifyOp` (`QueryEngineTDB.java:84-98`) runs `Algebra.toQuadForm(op)` after the
standard optimiser, so the algebra handed to the executor is quad form for **every** TDB2 query,
default graph included. Observed `ALGEBRA` lines (identical in sections A, B, C):

```
default-bgp     : (quadpattern (quad <urn:x-arq:DefaultGraphNode> ?a ex:knows ?b) (quad <urn:x-arq:DefaultGraphNode> ?b ex:livesIn ?c))
graph-named     : (quadpattern (quad <http://example.org/g1> ?a ex:knows ?b) (quad <http://example.org/g1> ?b ex:livesIn ?c))
graph-var       : (quadpattern (quad ?g ?a ex:knows ?b) (quad ?g ?b ex:livesIn ?c))
filter-last-var : (filter (!= ?c ex:graz) (quadpattern …two quads…))
filter-first-var: (sequence (filter (!= ?a ex:c) (quadpattern (quad DGN ?a ex:knows ?b))) (quadpattern (quad DGN ?b ex:livesIn ?c)))
```

There is no `OpBGP` anywhere in the executed tree; the in-memory control keeps `(bgp …)` /
`(graph <g1> (bgp …))`.

Whether our `execute(OpBGP)` is *still* reached depends on which executor class dispatches the
`OpQuadPattern`:

- **With `OpExecutorTDB2` (what actually runs today, sections A/B):** `OpExecutorTDB2.execute(OpQuadPattern)`
  goes straight to `optimizeExecuteQuads` → `PatternMatchTDB2` (`OpExecutorTDB2.java:154-168`).
  It never creates an `OpBGP`. Our override could not be reached even if our class were the one
  constructed *and* extended `OpExecutorTDB2` — we would have to override `execute(OpQuadPattern)`.
- **With a plain `OpExecutor` subclass (section C probe):** `OpExecutor.execute(OpQuadPattern)`
  (`OpExecutor.java:163-176`) converts the quad pattern back to `new OpBGP(quadPattern.getBasicPattern())`,
  either directly (default graph, identity check) or wrapped in `OpGraph` → `QueryIterGraph` /
  `specialcase` → `copyChangeActiveGraph` → `QC.execute` → a *new* executor from the same factory
  → `execute(OpBGP)`. That is why section C shows `invocations = 1` for `default-bgp`,
  `graph-named` **and** `graph-var`, with the active graph being a `GraphTDB` (the recorder
  saw `activeGraph=org.apache.jena.tdb2.store.GraphTDB` on the cyclic query in the same mode).
  Which of the two sub-routes fires for the default graph is not observable from the probe;
  by source it is the `OpGraph`/`specialcase` one, because `QueryEngineTDB` executes against
  the inner `DatasetGraphTDB`, whose `getDefaultGraph()` returns a **new** `GraphTDB` on every
  call (`DatasetGraphTDB.java:117-134`, `GraphTDB.java:52-53`), so the identity shortcut
  `activeGraph == dataset.getDefaultGraph()` is false. (The report's
  "identity-stable: true" line refers to the outer `DatasetGraphSwitchable` handle, which is
  not the object the engine holds.) Both routes end in our `execute(OpBGP)`.

`OpQuadBlock` did not appear in any executed plan (Jena 6 `toQuadForm` emits `OpQuadPattern`).

**Implication for benchmark validity:** on TDB2 the unit our executor is offered is an
`OpQuadPattern`, not an `OpBGP`. A fix that only registers per dataset (keeping
`extends OpExecutor`) works *by accident* of ARQ's quad→BGP down-conversion and, for the
`GRAPH ?g` case, evaluates the BGP once per named graph through `QueryIterGraph` — same as
stock ARQ but *not* what `OpExecutorTDB2` does (it matches `?g` as a quad variable in one index
scan). A fix that extends `OpExecutorTDB2` must intercept `execute(OpQuadPattern, …)` itself,
or the Yannakakis path is dead code on TDB2.

## Q4 — Does `(filter (bgp …))` still reach `execute(OpBGP, …)` in Jena 6?

Two different answers depending on which triple binds the filter variable, on **both** stores:

1. **Filter on a variable bound by the last triple** (`filter-last-var`): the optimiser leaves
   `(filter expr (bgp t1 t2))` (in-memory) / `(filter expr (quadpattern …))` (TDB2) intact.
   - In-memory + ours: plain `OpExecutor.execute(OpFilter)` (`OpExecutor.java:310-319`) executes
     the sub-op via `exec(base, input)` → our `execute(OpBGP)` → **invocations = 1**, then wraps
     `QueryIterFilterExpr`. Reached.
   - TDB2 + `OpExecutorTDB2` (today): `OpExecutorTDB2.execute(OpFilter)` (`OpExecutorTDB2.java:110-139`)
     intercepts `(filter (quadpattern))` *before* the sub-op is dispatched, runs
     `TransformFilterPlacement.transform(exprs, gn, bgp)` at execution time and executes the
     result with a **second, non-reordering executor** (`plainExecute` → `OpExecutorPlainTDB`,
     `OpExecutorTDB2.java:278-294, 388-442`). Even if our class extended `OpExecutorTDB2` and
     overrode `execute(OpBGP)`/`execute(OpQuadPattern)`, a filtered pattern would never reach
     those overrides unless `execute(OpFilter)` is overridden too.
   - TDB2 + PER_DATASET probe (plain `OpExecutor` base): reached, invocations = 1.
2. **Filter on a variable bound by an earlier triple** (`filter-first-var`): the *algebra
   optimiser* (`OptimizerStd.transformFilterPlacement`, `TransformFilterPlacement(pushIntoBGP=true)`
   because `ARQ.optFilterPlacementBGP` is undefined, `OptimizerStd.java:163-164, 245-251`)
   **splits the BGP**:

   ```
   (sequence
     (filter (!= ?a ex:c) (bgp (triple ?a ex:knows ?b)))
     (bgp (triple ?b ex:livesIn ?c)))
   ```

   Our executor is then invoked **twice on single-triple BGPs** (invocations = 2 in sections C,
   D, E). The join between the two triples is performed by ARQ's `OpSequence` (nested
   substitution, `OpExecutor.java:224-231`), not by the Yannakakis evaluator. This happens
   identically for the in-memory control.

**Implication for benchmark validity:** for filtered queries, whether the join tree is
exercised at all depends on *which* variable the filter mentions. Any benchmark query whose
filter binds early will show "Yannakakis ran" (`invocations > 0`) while the multi-way join was
actually done by stock ARQ — a silent false positive for the "acyclic path fired" check. The
semijoin reduction is also side-stepped: the filter is applied before the join, so
Yannakakis has no dangling tuples to remove. Results stay correct in all cases (rows matched).

## Q5 — Same questions for the in-memory control

| Question | In-memory `Model` (`QueryExecutionFactory.create(q, model)`) / general `Dataset` |
|---|---|
| Q1 factory | `YannakakisOpExecutor.FACTORY` in the query ctx; `YannakakisOpExecutor` constructed. The dataset wrappers (`DatasetGraphOne` for a Model, `DatasetGraphMap` for `DatasetFactory.create()`) carry **no** factory in their context, so the global setting is not overridden. |
| Q2 `super.execute(OpBGP)` | plain `OpExecutor.execute(OpBGP)` → global stage generator, which is **`StageGeneratorDirectTDB`** (installed by `TDB2.init()` just because jena-tdb2 is on the classpath). For a non-TDB graph it bounces to the wrapped `StageGeneratorGeneric` — the explain log shows `BGP` then `Reorder/generic`, i.e. stock ARQ's generic reorder + nested substitution. This is the correct stock behaviour; the extra `instanceof` hop is negligible. |
| Q3 quad rewriting | none. `QueryEngineMain` does not quadify; executed algebra is `(bgp …)`, `(graph <g1> (bgp …))`, `(graph ?g (bgp …))`. `GRAPH` clauses go through `OpExecutor.execute(OpGraph)` → `QueryIterGraph` → per-graph `QC.execute` → our `execute(OpBGP)` with the named graph active (invocations = 1 for both `graph-named` and `graph-var`). |
| Q4 filters | identical to TDB2: `(filter (bgp))` reaches us when the filter variable is bound by the last triple; the optimiser splits the BGP into a `(sequence …)` of single-triple BGPs when it is bound earlier (invocations = 2). |

**Implication:** the existing in-memory results (`docs/comparison-results.md`,
`DifferentialTest`, `EngineComparisonTest`) are valid as far as *executor selection* goes —
ours really runs there. The Q4 caveat (filter placement fragmenting BGPs) applies to them
too, but the current test queries have no filters on early-bound variables, so it has not
surfaced.

## Additional finding — data access path on TDB2

When our executor does run on TDB2 (section C), `Stage.nextStage` matches each triple pattern
with `Graph.find(s, p, o)` on a `GraphTDB` (`YannakakisOpExecutor.java:135-157`). Every
matched triple is materialised from `NodeId` to `Node` through the node table *before* joining,
and the `Relation` rows are `HashMap<Var, Node>` in Java heap. `OpExecutorTDB2` /
`PatternMatchTDB2` instead join on `NodeId` tuples (`BindingNodeId`) and decode to `Node`
only for the final bindings. A TDB2 benchmark therefore compares
"Yannakakis over Node-level `find()` + heap hash joins" against
"nested index loops over NodeId tuples"; the algorithmic effect is confounded with
materialisation cost. This is not a bug and needs no fix for correctness, but it must be
stated as a limitation when interpreting any TDB2 timings.

## Proposed fixes (not implemented)

Ordered by how much they change; each maps to a problem above.

### F1 — Register per dataset, not globally (fixes Q1)

`register()` currently writes to `ARQ.getContext()`, which TDB2 overrides. Instead offer

```java
public static void register(DatasetGraph dsg)   { QC.setFactory(dsg.getContext(), FACTORY); }
public static void unregister(DatasetGraph dsg) { QC.setFactory(dsg.getContext(), <saved>); }
```

and, for TDB2, save/restore the previous value (`OpExecutorTDB2.OpExecFactoryTDB`) rather than
`null`. `dsg.getContext()` is the same object for the `DatasetGraphSwitchable` handle from
`TDB2Factory.connectDataset` and for the inner `DatasetGraphTDB` that `QueryEngineTDB` actually
executes against, so setting it on the handle is sufficient (verified: section C constructs
`YannakakisOpExecutor` for every query).

Keep the global `register()` for `Model`-based callers (it is what the existing tests use),
but document that it is ineffective for TDB2. A per-`QueryExecution` alternative also exists —
`QueryExecution.create().dataset(ds).query(q).set(ARQConstants.sysOpExecutorFactory, FACTORY)` —
because the builder's `ContextAccumulator.buildProcess` layers `ARQ.getContext().copy()`, then
`putAll(dataset.getContext())`, then `putAll(<builder .set() values>)`
(`ContextAccumulator.java:141-159`, `QueryExecDatasetBuilder.java:68-69, 120-123`), so a
builder-level `set` outranks TDB2's dataset entry. Useful for A/B runs in a benchmark harness
without mutating dataset state. Source-confirmed only; not probed empirically in this audit.

Minimal per-dataset registration alone is **enough to make the Yannakakis path fire on TDB2**
(section C) but leaves Q2/Q3/Q4 as described: cyclic fallback loses TDB2 reordering, quad
patterns are down-converted by ARQ instead of handled natively, and `GRAPH ?g` is iterated
per graph.

### F2 — Extend `OpExecutorTDB2` for the TDB2 executor (fixes Q2, needed for Q3)

Recommended: a second executor class, e.g. `YannakakisOpExecutorTDB2 extends OpExecutorTDB2`,
selected by `register(DatasetGraph)` when `TDBInternal.isBackedByTDB(dsg)`; keep the existing
`YannakakisOpExecutor extends OpExecutor` for in-memory datasets. Rationale:

- `super.execute(OpBGP)` / `super.execute(OpQuadPattern)` / `super.execute(OpFilter)` then resolve
  to `OpExecutorTDB2`'s versions → the cyclic fallback is *exactly* stock TDB2 including
  reordering and the explain fingerprint (`TDB2`, `Execute` lines) — a fair baseline.
- `OpExecutorTDB2(ExecutionContext)` is public; `OpExecutorTDB2.decideGraphNode(Node, ExecutionContext)`
  is public static. `isForTDB` is private, but is just `execCxt.getActiveGraph() instanceof GraphTDB`.

What has to be overridden (all `protected` in `OpExecutorTDB2`, `OpExecutorTDB2.java:110-168`):

1. `execute(OpQuadPattern, input)` — this is the op TDB2 actually dispatches (Q3). Resolve the
   graph node with `decideGraphNode(gn, execCxt)`: `null` → default-graph storage
   (`dsg.getDefaultGraph()`), `Node.ANY` → union graph (`dsg.getUnionGraph()`), a concrete IRI →
   `dsg.getGraph(gn)`, a **variable** → delegate to `super` (TDB2 binds `?g` inside one quad index
   scan; replicating that in the Yannakakis evaluator would need a 4-position hyperedge, which
   the rank-3 rule in CLAUDE.md forbids — so `GRAPH ?g { … }` stays stock). With the `Graph` in
   hand, run acyclicity test + the existing `Stage` unchanged; otherwise `super.execute(quadPattern, input)`.
2. `execute(OpBGP, input)` — still needed for the non-quadified paths (`OpGraph` sub-ops such as
   property paths, `DynamicDatasets`, TDB graph inside a general dataset). Same body as today
   with `super` now being TDB2's.
3. `execute(OpFilter, input)` — because `OpExecutorTDB2` intercepts `(filter (quadpattern))` /
   `(filter (bgp))` and never dispatches the sub-op (Q4 case 1). Minimal handling: if the
   sub-op is an acyclic `OpQuadPattern`/`OpBGP`, run it through (1)/(2) and wrap the result in
   `QueryIterFilterExpr` per expression (what plain `OpExecutor.execute(OpFilter)` does);
   otherwise `super.execute(opFilter, input)` so cyclic filtered patterns get TDB2's filter
   placement + `OpExecutorPlainTDB`. Note the trade-off: this puts the filter *after* the full
   join for acyclic patterns, whereas stock TDB2 pushes it into the pattern; that is a
   deliberate choice (semijoin reduction is the thing being measured) and must be stated.

Not needed: `exec(Op, QueryIterator)` (keep TDB2's explain), `execute(OpGraph)`,
`execute(OpDatasetNames)`.

### F3 — Stop the optimiser from fragmenting BGPs under filters (fixes Q4 case 2)

Set `ARQ.optFilterPlacementBGP = false` in the context of the Yannakakis-registered dataset (or
per `QueryExecution`) — this makes `OptimizerStd` build `TransformFilterPlacement(false)`,
which keeps filters *around* BGPs instead of pushing them *into* them, while still placing
filters relative to joins/optionals. Do **not** set `ARQ.optFilterPlacement = false` wholesale
(it also disables placement across `OpJoin`/`OpLeftJoin`, changing more than needed). Apply
the same flag to the stock run in a benchmark, or record that the two engines were run with
different optimiser settings — either way it must be explicit, because the flag changes the
stock plan too.

Alternatively, treat "filter splits BGP" as out of scope and restrict benchmark queries to
filters on late-bound variables or no filters; the differential harness should then assert on
the executed `ALGEBRA` (or on the *number of triples per invocation*) rather than only on
`invocations() > 0`.

### F4 — Make the "Yannakakis fired" check trustworthy on TDB2 (test infrastructure)

Add a TDB2 nested class to `DifferentialTest` mirroring the in-memory one, using the
per-dataset registration from F1, and have `resetCounter()/invocations()` also record the
`BasicPattern.size()` of each Yannakakis invocation so the assertion can require
`size >= 2` for the acyclic multi-triple cases (guards against Q4 case 2 false positives).
Keep the TDB2 store under `target/` in a timestamped directory (no `@AfterEach` delete on
Windows).

### F5 — Data access on `NodeId` tuples (optional, larger)

Only if TDB2 timings are meant to isolate the join algorithm from materialisation cost:
match triple patterns through `dsgtdb.getTripleTable()/getQuadTable().find(...)` on
`Tuple<NodeId>` and run `Relation`/semijoins on `NodeId`, decoding at the end. This is a
significant change to `Relation` and `YannakakisEvaluator`; not recommended before F1–F4
are in and a TDB2 differential suite passes.

## Verification run

```
mvn test
…
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in at.ac.tuwien.thesis.yannakakis.Tdb2AuditScratchTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The scratch test restores the global ARQ executor factory, the stage generator and the explain
level in `@AfterAll`; the rest of the suite is unaffected. Each run leaves a new
`target/tdb2-audit/<stamp>/` directory (store + `report.txt`); delete the test (and the
directories after a JVM restart) once the findings have been acted on.
