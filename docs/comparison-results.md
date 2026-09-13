# Engine comparison: stock ARQ vs. `YannakakisOpExecutor`

Produced by `EngineComparisonTest#compareEnginesAcrossQueries`. For each query below, the
same in-memory model is queried twice — once through unmodified ARQ, once with
`YannakakisOpExecutor` registered via `QC.setFactory` — and the two result bags are
compared in canonical form (variables sorted alphabetically within a row, rows sorted).
The test asserts the bags are equal; that assertion, not the timings, is the point of
this test. "fired" reports whether `YannakakisOpExecutor.invocations()` increased during
the run, i.e. whether the BGP was actually routed through the Yannakakis path rather than
delegated to stock ARQ.

**This is not a benchmark.** Each query runs exactly once per engine with no warm-up, so
JIT/classloading noise dominates — see the `single-triple` row in a cold JVM, which paid a
~60ms one-time cost that vanished (down to ~0.4ms) once run after the rest of the suite had
already warmed up the same classes. Do not read the ms columns as evidence of which engine
is faster; the queries and model here are too small for that to be meaningful either way.

## Results (run after the rest of the suite, JIT-warmed)

| query                    | results | default ms | yannakakis ms | fired |
|--------------------------|---------|------------|---------------|-------|
| single-triple            | 5       | 0.435      | 0.256         | true  |
| path                     | 3       | 0.281      | 0.325         | true  |
| star                     | 6       | 0.558      | 0.530         | true  |
| snowflake                | 1       | 0.329      | 0.370         | true  |
| disconnected-components  | 6       | 0.215      | 0.421         | true  |
| cyclic-triangle          | 3       | 0.282      | 0.234         | false |

## Query shapes

- **single-triple** — one triple pattern, no join at all (`?x ex:knows ?y`).
- **path** — a 3-hop linear chain (`knows` → `livesIn` → `country`), acyclic.
- **star** — one central variable (`?p`) joined by three triples radiating out from it.
- **snowflake** — a central variable with two branches, each extended one further hop.
- **disconnected-components** — two triples sharing no variable; the BGP is two separate
  connected components, still trivially acyclic.
- **cyclic-triangle** — `knows(x,y)`, `knows(y,z)`, `knows(x,z)`: the classic 3-cycle. GYO
  reduction cannot remove any ear, so `YannakakisOpExecutor` delegates to stock ARQ
  unchanged (`fired = false`), and both engines still agree on the result.
