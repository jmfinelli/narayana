# Narayana GitHub Actions CI — Structural Improvement

**Date:** 2026-10-27
**Branch of work:** `improve_github_actions` (fork: `jmfinelli/narayana`)
**Status:** Design, pending plan

## Intent and why

The Narayana CI under `.github/workflows/` is architecturally sound. It
already does the hard, right things: it builds Narayana + WildFly once per
JDK and uploads them as run-scoped artifacts for reuse; it splits
long-running test suites into parallel matrix shards to stay under GitHub's
6-hour job ceiling; and it drives axis selection from the PR description
with a well-thought-out parser. (The reuse is only *partly* realised today —
CORE and JACOCO reuse the cached WildFly, but RTS/XTS re-derive it; see
Intent #2 — which is exactly the kind of thing this tidy-up addresses.)

The author wants a second opinion and a structural tidy-up — **not a
redesign**. Reading the whole suite end to end surfaced four categories of
improvement, all of which the author has chosen to pursue:

1. **Duplication** — the per-job preamble (log context → checkout →
   setup-java → purge Narayana/WildFly from the local m2, plus the git
   identity set *inline* at the top of each test step's `run:` block) is
   copy-pasted across ~10 workflow files, and the artifact-restore block
   (download m2 → download WildFly zip → unzip) across the four that consume
   the build (`core`/`rts`/`xts`/`jacoco`). The per-database setup
   `if`-chain (the four `*_setup_ubuntu_runner` calls) is duplicated **four
   times** (`db_tests.yml` twice, `qa_jta.yml`, `jacoco.yml`'s qa job), and
   the DB2 `docker stop/rm` teardown is repeated. The database JDBC-string
   bash block (~18 lines) is duplicated **twice** — only in the two jobs of
   `db_tests.yml` (it is the sole workflow that computes a `JDBC_ACCESS`
   string; `qa_jta`/`jacoco` set databases up but don't build that string).
2. **Inconsistent build reuse** — the cached build is reused unevenly, and
   three build mechanisms coexist. `as_builder.yml` builds Narayana + WildFly
   via `narayana.sh` and uploads the m2 tree + WildFly zip. Of its four
   consumers, **CORE and JACOCO reuse the cached WildFly** (they pass
   `JBOSS_HOME` + `AS_BUILD=0`, so `narayana.sh` calls `init_jboss_home`
   instead of rebuilding) while still rebuilding Narayana; but **RTS and XTS
   reuse nothing** — they pass `AS_BUILD=0 NARAYANA_BUILD=0` on the command
   line, yet their `PROFILE=RTS|XTS` preset inside `narayana.sh`
   *unconditionally* re-`export`s `AS_BUILD=1 NARAYANA_BUILD=1`, overriding
   the command line and rebuilding **both** Narayana and WildFly — so the
   artifacts they download are wasted. Separately, `performance.yml` has its
   own `build-narayana` job using `build.sh` directly (m2 only, different
   artifact name `narayana-m2-*`), and the remaining axes build from scratch:
   `AS_TESTS` (`PROFILE=AS_TESTS` → builds WildFly + Narayana),
   `qa_jta`/`qa_jts_openjdkorb`/`db_tests` (explicit `NARAYANA_BUILD=1`, no
   WildFly), and `experimental` (raw `mvn install`, never calls
   `narayana.sh`).
3. **At-the-ceiling workflow nesting** — on PR/dispatch the chain
   `axes_selector` → `as_builder` → `core/rts/xts/jacoco` is three levels,
   but via the scheduled entry point it is **four**: `daily_job` →
   `axes_selector` (`workflow_call`) → `as_builder` → `core` — i.e. the
   daily path already sits at GitHub's 4-level cap with zero headroom. The
   structure is also asymmetric: CORE/RTS/XTS/JACOCO are dispatched *inside*
   the builder workflow, while AS_TESTS/QA/DB/PERFORMANCE/QUICKSTART/
   EXPERIMENTAL are dispatched directly from the selector.
4. **Hygiene gaps** — floating (non-SHA) action tags survive in **three**
   places, not one: `experimental.yml` (`checkout@v7`, `github-script@v9`,
   `upload-artifact@v7`), `daily_job_experimental.yml` (`checkout@v7`), and —
   most important, because it is **third-party** —
   `actions/oracle_setup_ubuntu_runner` (`gvenzl/setup-oracle-free@v1`).
   Plus a naming inconsistency (`name: db_tests.yml` carries the file
   extension); and the real cross-cutting "API" — `narayana.sh`'s large
   env-var contract — is undocumented at the workflow layer and is invoked
   two different ways (`PROFILE=…` vs explicit flag soup).

**Why now:** the suite works, which is exactly the right moment to pay down
structural debt — before the duplication makes the next functional change
risky to apply consistently across ten files.

## Goals

- Remove the bulk of the copy-pasted YAML by extracting a small set of
  **composite actions** under `.github/actions/`, so each axis workflow
  reads as "what is different about this axis" rather than boilerplate.
- Make build-and-reuse **consistent and deliberate**: one canonical
  mechanism for producing the cached Narayana m2 (+ WildFly zip where
  relevant), and an explicit, validated decision for every axis about
  whether it reuses the cached build or rebuilds.
- **Flatten** the reusable-workflow nesting so all axes are dispatched
  uniformly from `axes_selector`, leaving comfortable headroom under the
  4-level cap.
- Close the **hygiene** gaps: SHA-pin every action (including the
  third-party Oracle action), normalise names, **standardise** the DB2
  teardown across the DB-using jobs (it stays caller-side), and add
  lightweight CI-config linting.
- Preserve existing behaviour at each step, with parity verified on the
  fork (see Verification).

## Non-goals

- Changing *which tests run*, their pass/fail semantics, or their coverage.
- Rewriting `narayana.sh`'s internal logic (documenting and standardising
  how it is *invoked* is in scope; refactoring what it *does* is not).
- Introducing self-hosted runners, caching services, or third-party CI
  actions beyond well-known first-party ones already in use.
- Changing the PR-description axis-selection DSL (the `CORE`, `!XTS`,
  `JDK21`, `BENCHMARKS=…` grammar) — users rely on it as-is.
- Touching the application/product build (`build.sh`, Maven POMs) except
  where a workflow invokes them.

## Anti-goals (what would make this a failure even if it "works")

- A "big bang" rewrite that lands all four points at once and can't be
  bisected when a nightly run goes red.
- Composite actions so generic/parameterised that a reader must jump
  through three files to understand one axis — trading copy-paste for
  indirection with no net clarity gain.
- Silently changing an axis's build (point 2) such that it still *passes*
  but no longer exercises what it used to (e.g. dropping a profile, or
  reusing an m2 that lacks test-scoped classes a surefire run needs).
- Increasing CI cost: verification burns real Actions minutes on long axes, so
  a process that re-runs the full 6-hour suite on every tiny change is a
  failure of the plan even if the YAML is clean.

## Constraints

- **Verification is fork + `workflow_dispatch`.** There is no faithful
  local execution (services, DBs, artifacts, 6h jobs). Every change is
  proven by dispatching the affected axes on `jmfinelli/narayana` and
  comparing job conclusions (and roughly, durations) against a known-good
  baseline run on the pre-change code.
- **CI minutes are tight, and verification is fork-only.** No upstream
  (`jbosstm`) dispatch. Verify with a single JDK (25) and a reduced
  database/benchmark subset throughout; dispatch only the axes a given
  phase actually touches. Reserve a single fuller-matrix confirmation for
  the very end (after Phase 4) rather than repeating it at every phase
  boundary.
- **All axes currently reach green on the fork** — confirmed by the author,
  including Oracle and DB2 (images pull, required secrets/vars are
  configured). So the full baseline is achievable on the fork as the plan
  assumes, and DB verification (Phase-2 `setup-database`, Phase-4 DB reuse
  audit) has a usable oracle.
- **GitHub limits:** reusable-workflow nesting ≤ 4 levels (the daily/cron
  path is already at 4 — see Intent #3); matrix-job outputs don't compose
  cleanly across `needs`, so cross-job hand-off should lean on
  *deterministic artifact names* rather than passing matrix outputs. Note
  the names are only *partly* deterministic today: the WildFly zip is
  `${run_id}-jboss-as-zip-jdk${jdk}` (fully reconstructable), but the
  Narayana m2 artifact is
  `${run_id}-narayana-${narayana_version}-artifacts-jdk${jdk}` and embeds
  the Maven version. Phase 3 must either drop the version from that name
  (behaviour-safe) or have the consumer recompute it — builder's call.
- **The fork's cleanup cron has no owner guard.** `cleanup_cron_job.yml`
  (unlike the two daily jobs) is *not* gated on
  `github.repository_owner == 'jbosstm'`, so it runs weekly on the fork and
  deletes successful runs. The baseline lives in the PR/scratch doc, not as
  a live run, so this is a documented awareness item, not a blocker.
- **Fork secret/environment parity:** some setups (Oracle/DB2 container
  images, `GITHUB_TOKEN` scopes for the cleanup cron) may behave
  differently on a fork; the baseline run must be captured on the *same*
  fork so comparisons are apples-to-apples.
- **Pinned-SHA policy:** all third-party and first-party actions pinned by
  commit SHA with a trailing `# vX` comment, matching the existing
  convention.

## Current architecture (reference map)

Entry points:
- `axes_selector.yml` — triggered by `pull_request` and `workflow_dispatch`
  (and called by the daily crons via `workflow_call`). Parses the PR body
  (or dispatch inputs) into per-axis booleans, JDK list, enabled databases,
  env vars, and benchmark groups, then dispatches each selected axis.
- `daily_job.yml` / `daily_job_experimental.yml` — scheduled; gate on
  "commits in last 24h" then call `axes_selector` with fixed inputs.
- `cleanup_cron_job.yml` — weekly; deletes successful runs for non-open PRs.

Build + reuse:
- `as_builder.yml` — builds Narayana + WildFly once per JDK (`narayana.sh`
  with `NARAYANA_BUILD=1 AS_BUILD=1`), uploads the Narayana m2 tree and the
  WildFly dist zip as run-scoped artifacts, then **internally** dispatches
  `core.yml`, `rts.yml`, `xts.yml`, `jacoco.yml`. Of these, CORE and JACOCO
  genuinely reuse the WildFly zip; RTS and XTS download it but rebuild (see
  Intent #2).
- `performance.yml` — self-contained: its own `build-narayana` job
  (`build.sh … -Pcommunity -Dexperimental -DskipTests`), uploads m2 under
  `narayana-m2-jdk*`, then fans out benchmark groups, then collects.

Axes dispatched directly from `axes_selector`:
- `as_tests.yml`, `qa_jta.yml`, `qa_jts_openjdkorb.yml`, `db_tests.yml`,
  `quickstart.yml`, `experimental.yml` (and `performance.yml`).

Shared assets:
- `.github/scripts/narayana.sh` — the entry point for most axes. Either
  `PROFILE=<AXIS>` sets a canned flag combination, or the caller sets the
  individual flags (`NARAYANA_BUILD`, `AS_BUILD`, `QA_TESTS`, `XTS_TESTS`,
  …). Actual usage: **RTS/XTS/AS_TESTS** use `PROFILE=`; **CORE, JACOCO,
  QA_JTA, QA_JTS_OPENJDKORB, DB_TESTS** use explicit flags; **PERFORMANCE**
  and **experimental** don't call `narayana.sh` at all (they run `build.sh` /
  `mvn` directly). Consequently the `PROFILE=CORE`, `JACOCO`, `QA_JTA`,
  `QA_JTS_OPENJDKORB`, `PERFORMANCE`, and `DB_TESTS` presets in the script are
  **dead code** (nothing invokes them — CORE/JACOCO/QA/DB use explicit flags),
  and the `PROFILE=RTS|XTS` presets force
  `AS_BUILD=1 NARAYANA_BUILD=1` regardless of the caller's command-line flags
  (the RTS/XTS rebuild noted in Intent #2).
- `.github/actions/{postgres,mysql,oracle,db2}_setup_ubuntu_runner/` —
  existing composite actions for DB bring-up.
- `.github/databases/*/init.sql`, `.github/actions/*`.

## The approach — decided

The work is sequenced into phases, **ordered lowest-risk first**, so that
structural changes land only after the surrounding churn is already proven
green. Each phase is an independently reviewable, independently mergeable
unit with its own fork verification.

### Phase 0 — Tooling + baseline (no behaviour change)

- Add **`actionlint`** (as a lightweight workflow and/or a pre-commit-style
  check) so every subsequent phase gets static validation of workflow
  syntax, expressions, and `uses:` references for free.
- Add a **pinned-action check** — a small grep-based CI step that fails on
  any non-SHA `uses:` (actionlint itself does *not* check action pinning) —
  so point-4 regressions can't creep back.
- **Capture a baseline:** dispatch `axes_selector` on the fork with all
  axes enabled on the *current* code, for JDK 25 (full DB set), and record
  for each job: its conclusion, its duration, **and its per-axis test
  counts** (surefire/failsafe report counts, QA `testoutput` tallies). This
  last part is the oracle for the Phase-4 "same tests actually ran" check,
  and it does not exist today: the log uploads are conditional
  (`if: failure()`) and build artifacts use `retention-days: 1`. **So the
  baseline run must be instrumented to upload and retain test-report counts
  unconditionally** — otherwise Phase 4 has no reference and the baseline
  must be re-run (expensive). This is the builder's reference artifact, kept
  in the PR description / a scratch doc, not committed.

### Phase 1 — Hygiene (point 4), behaviour-preserving

- SHA-pin the floating first-party actions in `experimental.yml` and
  `daily_job_experimental.yml` (`checkout`, `github-script`,
  `upload-artifact`) to the exact SHAs already used elsewhere in the suite.
- Pin the **third-party** `gvenzl/setup-oracle-free@v1` in
  `actions/oracle_setup_ubuntu_runner` — its SHA isn't used elsewhere, so it
  must be looked up for the `v1` release and pinned (`# v1`).
- Sweep all workflows **and composite actions** for any remaining floating
  tags; pin them.
- Fix cosmetic names (`name: db_tests.yml` → `name: db_tests`, and audit
  the rest for consistency).
- Lowest risk; verify with `actionlint` + a single dispatched smoke axis.

### Phase 2 — Composite actions (point 1), behaviour-preserving

Extract, one composite at a time, applying + verifying each across all
consumers before moving to the next. Candidate composites (final interface
left to the builder — see "Left to the builder"):

- `prepare-build-env` — setup-java (temurin, maven cache) + purge
  Narayana/WildFly from local m2 + git identity. It does **not** checkout:
  a local composite can only run once the repo is on disk, so checkout stays
  an inline step in each workflow and this composite runs after it. (Note
  `quickstart.yml` differs — custom `cache-dependency-path`, clones an
  external repo — so it may use only part of this, or opt out.)
- `restore-narayana-build` — download the Narayana m2 artifact + download
  and unzip the WildFly dist zip into `./wildfly`.
- `setup-database` — single action taking `database` (and `target_db`),
  dispatching to the right existing DB composite and exposing the computed
  `JDBC_ACCESS` string as an output (replacing the four-way setup `if`-chain
  and, for `db_tests`, the JDBC-string bash block). The
  `dbjbossts-properties.xml` templating and the DB2 `docker stop/rm`
  teardown stay **caller-side** — a composite can't own the caller's
  `if: always()` teardown. Note `jacoco.yml`'s qa job brings up all four
  databases at once, so it invokes this action once per database. For
  consistency, `jacoco-qa-test` also **gains** a caller-side DB2 teardown
  (it has none today) — a deliberate, cosmetic addition so all DB-using
  jobs behave alike (harmless on ephemeral runners; insurance against
  runner reuse).
- `load-env-vars` — the `github-script` block that explodes the
  `environment_variables` JSON into exported env vars.
- `upload-failure-logs` — the failure/cancel log-upload step, **scoped to
  the axes that share the common log glob** (core/rts/xts/as_tests/jacoco),
  parameterised by `name` (default path = the common glob). QA, performance,
  and quickstart keep their uploads inline — they'd only override the path,
  so the composite is limited to where it removes real duplication.

Each composite is introduced and rolled out behaviour-identically; the
diff for each consumer should be "delete N lines, add one `uses:`".

### Phase 3 — Flatten nesting (point 4/structural), behaviour-preserving

- Split `as_builder.yml` into a **pure builder** reusable workflow that
  only builds + uploads artifacts — it no longer dispatches
  CORE/RTS/XTS/JACOCO.
- Dispatch `CORE/RTS/XTS/JACOCO` directly from `axes_selector` with
  `needs: <builder>`. Because the builder runs as a per-JDK matrix, its
  per-leg outputs can't be selected downstream, so consumers **reconstruct**
  the artifact names from `run_id` + jdk. The WildFly zip name already
  reconstructs cleanly, but the Narayana m2 name embeds the Maven version —
  so this phase must first **drop the version from that name** (producer and
  consumer updated together, behaviour-safe) to make reconstruction reliable.
- Net effect: all axes become siblings under `axes_selector`; PR/dispatch
  nesting drops from 3 to 2 levels (the daily/cron path from 4 to 3);
  dispatch is uniform. Verify the full CORE/RTS/XTS/JACOCO set on the fork
  against baseline. (RTS/XTS still download-then-rebuild here — Phase 3
  preserves that; making them *true* consumers is a Phase-4 item.)

### Phase 4 — Build-reuse consistency (point 2), **behaviour may change** — last, gated

This is the riskiest work and runs only after Phases 1–3 are green.

- **Unify the two producers:** fold `performance.yml`'s internal
  `build-narayana` into the same canonical builder/artifact used by the
  flattened Phase-3 design (consumed via `restore-narayana-build`), or
  document a deliberate reason it must differ. Known subtlety to resolve:
  the performance builder runs `build.sh -Pcommunity -Dexperimental
  -DskipTests` (experimental reactor, no WildFly), whereas the canonical
  `as_builder` runs `narayana.sh` `build_narayana` with `-Pcommunity`, no
  `-Dexperimental`, *plus* builds WildFly. Folding them naively risks an m2
  that lacks (or adds) modules the benchmarks assume — validate the
  unified artifact actually satisfies the benchmark run before switching.
- **Per-axis reuse audit:** for each axis that rebuilds today, determine
  whether it can safely reuse the cached m2 (and/or cached WildFly) instead.
  The mechanism differs per axis — it is *not* uniformly "drop
  `NARAYANA_BUILD=1`":
  - **RTS, XTS** — the biggest opportunity: they already download the m2 +
    WildFly artifacts but their `PROFILE=RTS|XTS` preset forces
    `AS_BUILD=1 NARAYANA_BUILD=1`, rebuilding both. Preferred fix: **give them
    explicit flags** (an invocation-level change) rather than editing the
    `narayana.sh` preset — rewriting the script's internals is a Non-goal — so
    the downloaded artifacts are actually used.
  - `CORE`, `JACOCO` — already reuse WildFly; they rebuild Narayana and run
    Narayana surefire tests, which need test-scoped compiled classes the
    published m2 may not contain — so the Narayana rebuild may be
    unavoidable. Decide and document; also check whether the m2 download they
    do is even used (it may be dead weight).
  - `AS_TESTS` — `PROFILE=AS_TESTS` builds WildFly *and* Narayana and runs
    the AS testsuite; reusing the cached WildFly (like CORE) is the target.
  - `qa_jta`, `qa_jts_openjdkorb`, `db_tests` — explicit `NARAYANA_BUILD=1`,
    ant-based QA suite with different profiles — reuse may be partial or
    infeasible.
  - `experimental` — runs raw `mvn install` (no `narayana.sh`, no
    `NARAYANA_BUILD`): it builds core then the `experimental/` reactor. The
    question is whether its core build can come from the cached m2 rather
    than a fresh `mvn install`.
  Each axis that switches is validated **individually** on the fork
  (conclusion parity *and* a spot-check that the same tests actually ran,
  not just that the job went green) before the next is touched.
- Optionally (and separately): document the `narayana.sh` env-var contract
  in a short header comment, and clean up the invocation inconsistency
  (CORE/JACOCO/QA/DB use explicit flags; RTS/XTS/AS_TESTS use `PROFILE=`).
  This is documentation/consistency, not behaviour change. **Cautions:**
  (a) several `PROFILE=` presets (`QA_JTA`, `QA_JTS_OPENJDKORB`,
  `PERFORMANCE`, `DB_TESTS`) are **dead code** — nothing invokes them — so
  "standardise on `PROFILE=`" would mean trusting presets no run has ever
  exercised; (b) the `PROFILE=RTS|XTS` presets force
  `AS_BUILD=1 NARAYANA_BUILD=1`, the very override that defeats RTS/XTS reuse
  (Intent #2 / the audit above), so touching these presets interacts with
  the reuse work. Prefer to document first and change presets only alongside
  the Phase-4 RTS/XTS reuse fix.

### Verification loop (every phase)

1. Open a PR on the fork for the phase.
2. `actionlint` must pass.
3. Dispatch **only the axes this phase touches** via `workflow_dispatch`
   (JDK 25, reduced DB/benchmark subset — minutes are tight, fork only).
4. Compare job conclusions to baseline; investigate any delta before merge.
5. Merge the phase; it becomes the new baseline for the next.
6. **End-of-project only:** after Phase 4, run one fuller-matrix
   confirmation (all JDKs / all DBs) once — not at every phase boundary.

## Left to the builder

- The exact **interface of each composite action** (input names, defaults,
  how much of `prepare-build-env` `quickstart.yml` reuses vs. overrides)
  — settle during Phase 2 against the real diffs.
- The precise **mechanism for reconstructing artifact names** across jobs
  in Phase 3 (helper step vs. inline expression), and whether the builder
  stays a `workflow_call` file or becomes a job.
- The **per-axis reuse decisions** in Phase 4 — these require investigation
  and fork validation; the design fixes the *method* (audit + individual
  validation + "same tests ran" spot-check), not the per-axis outcome.
- Choice of **actionlint integration** (dedicated workflow vs. a step in an
  existing one vs. pre-commit) and the exact pinned-action guard.
- Whether to also standardise the `narayana.sh` invocation convention now
  or defer it (it is explicitly optional in Phase 4).
