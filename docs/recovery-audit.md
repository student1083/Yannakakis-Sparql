# Recovery audit: AlgebraContextAnalyzer / RelationBridge / free-connex / etc.

Requested by `docs/claude-code-plan.md` step 1, on the premise ("my notes on this project
record ... as already built") that a Phase 5b existed and was lost when the
`YannakakisQueryEngine`/`YannakakisTransform` scaffold was deleted (`docs/today.md` step 3,
commit `d28af4a`).

**Finding: none of the six items ever existed in this repository, at any commit, on any
branch, or as a dangling/unreachable object. There is nothing to restore. Steps 6 and 7 are a
build from scratch, not a recovery.**

## Method

- `git branch -a` / `git tag`: only `main` (+ `origin/main`), no tags.
- `git reflog --all`: 15 entries total, all on `main`, matching `git log --oneline` exactly —
  no orphaned commits ever pointed to by a ref or reflog entry.
- `git fsck --full --unreachable --no-reflogs`: 17 unreachable objects, **all blobs, zero
  commits or trees**. Dumped every one (see below) — all are either single-line empty class
  stubs (`YannakakisEvaluator`, `HypergraphDemo`, `GyoDemo`, `QueryHypergraph`,
  `YannakakisOpExecutor`, `Relation`, `YannakakisArqDemo`, `GyoReduction`,
  `GyoReductionTest`, `AlgebraExplorer`, `YannakakisIntuitionDemo`,
  `YannakakisOpExecutorTest`, `JoinTree`, `QueryHypergraphTest`, `YannakakisEvaluatorTest` —
  leftover scaffold blobs from before Phase 0–4 filled the files in, superseded and never
  referenced by any tree) or one stray `settings.local.json` permission blob and one empty
  blob. None relate to any of the six items.
- `git log --all -S"<term>"` and `-G"<term>"` (pickaxe, added/removed occurrences, all
  branches, whole history) for `AlgebraContextAnalyzer`, `RelationBridge`, `free-connex`,
  `freeConnex`, `relationDominated`, `relation-dominated`, `YannakakisOpExecutorFactory`,
  `YannakakisRunner`: **zero hits for every term.**
- `git log --all --full-history --diff-filter=A --name-only` for filename globs
  `*AlgebraContextAnalyzer*`, `*RelationBridge*`, `*YannakakisOpExecutorFactory*`,
  `*YannakakisRunner*`, `*FreeConnex*`, `*OutputAware*`: **no such file was ever added in any
  commit.**
- Broader prose search (`git grep` over every commit in `git rev-list --all`) for the
  underlying concepts — "output variable", "augmented hypergraph", "root selection",
  "early-projecting", "bottom-up fold", "extra atom" — turned up exactly one hit, the phrase
  "bottom-up fold" in `CLAUDE.md`'s working-style section (present since commit `dd8a56d`,
  the initial commit). That line reads "Keep correctness (from the bottom-up fold) separate
  from output-sensitivity (from the semijoin reduction)" — it refers to the existing naive
  fold-join oracle used in `YannakakisEvaluatorTest`/`DifferentialTest`, not to an
  early-projecting variant of the real evaluator. No other hit anywhere.
- Cross-checked against `docs/implementation-inventory.md` (written today, step 4, from the
  current source tree) and the current `src/main/java/.../yannakakis/` + `src/test/...`
  listing: 12 production/demo classes + 6 test classes, all named in the inventory table
  above. No trace of any of the six items in either list.

## Per-item results

| Item | Introduced (commit) | Removed (commit) | File path | Currently present |
|---|---|---|---|---|
| `AlgebraContextAnalyzer` (or any Op-tree walker computing per-BGP output variables) | never | — | — | No |
| `RelationBridge` | never | — | — | No |
| Free-connex classification (augmented hypergraph with an extra atom over output variables) | never | — | — | No |
| Relation-dominated detection / output-aware root selection | never | — | — | No |
| Early-projecting bottom-up fold in the evaluator | never | — | — | No (the only "bottom-up fold" in the repo is the ordinary naive-join test oracle, present since the initial commit, unrelated to output-projection) |
| `YannakakisOpExecutorFactory` | never | — | — | No |
| `YannakakisRunner` | never | — | — | No |

## What actually happened to `d28af4a` (the one real deletion in this history)

The only deletion commit in the whole history is `d28af4a` ("Step 3: delete superseded
YannakakisQueryEngine/YannakakisTransform scaffold"), which removed exactly two files:
`YannakakisQueryEngine.java` and `YannakakisTransform.java` (the abandoned
`Transform`/`QueryEngineMain` integration attempt described in `CLAUDE.md`). Neither of those
two files, nor their prior content at `d28af4a^` (`0939cca`), contains any of the six items
either — confirmed by the same pickaxe search above, which covers their full history since
they never existed outside commits already scanned.

## Restore commands

Not applicable — there is no prior revision of any of the six items to restore. If a restore
command is needed later for `YannakakisQueryEngine.java` / `YannakakisTransform.java`
specifically (unrelated to this audit's six targets), the last revision before deletion is at
`d28af4a^` (`0939cca`):

```
git show 0939cca:src/main/java/at/ac/tuwien/thesis/yannakakis/YannakakisQueryEngine.java
git show 0939cca:src/main/java/at/ac/tuwien/thesis/yannakakis/YannakakisTransform.java
```

## Note on the source of the claim

`docs/claude-code-plan.md` step 1 is the only place in this session where the claim "my notes
... record [these] as already built" appears. That file itself has never been committed
(`git log --all -- docs/claude-code-plan.md` returns nothing) and currently exists only as a
staged-but-worktree-deleted entry in the index (`git status` shows `AD
docs/claude-code-plan.md`) — it is not part of repository history and was not produced by any
prior Claude Code session captured in `git log`. Whatever "notes" the premise refers to, they
do not describe this repository's actual history. Steps 6/7 in that plan should be scoped as
new implementation work, not recovery.

No source files were modified, and nothing was restored, in the course of this audit.
