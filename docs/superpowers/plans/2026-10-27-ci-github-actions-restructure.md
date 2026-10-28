# Narayana CI GitHub Actions Restructure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove duplication, unify build reuse, flatten workflow nesting, and close hygiene gaps in the Narayana GitHub Actions CI without changing what the CI tests.

**Architecture:** Five phases ordered lowest-risk first, each an independently mergeable PR on the fork. Hygiene and static tooling first; then extract shared logic into composite actions under `.github/actions/`; then flatten the `axes_selector → as_builder → axes` nesting so all axes are siblings; finally unify the two build mechanisms and audit per-axis build reuse (the only phase that may change behaviour). Verification is `actionlint` plus `workflow_dispatch` on the fork compared to a captured baseline.

**Tech Stack:** GitHub Actions (reusable workflows + composite actions), `actionlint`, bash, Maven/`narayana.sh`.

**Spec:** `docs/superpowers/specs/2026-10-27-ci-github-actions-restructure-design.md`

## Global Constraints

- **Verification is fork-only** (`jmfinelli/narayana`); no upstream (`jbosstm`) dispatch.
- **CI minutes are tight:** iterate with JDK 25 and a reduced DB/benchmark subset; dispatch only the axes a phase touches; reserve one fuller-matrix run for after Phase 4.
- **All actions pinned by commit SHA** with a trailing `# vX` comment, matching the existing convention. Reuse the exact SHAs already present in the repo:
  - `actions/checkout` → `3d3c42e5aac5ba805825da76410c181273ba90b1 # v7`
  - `actions/setup-java` → `b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0`
  - `actions/github-script` → `3a2844b7e9c422d3c10d287c895573f7108da1b3 # v9`
  - `actions/upload-artifact` → `cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7`
  - `actions/download-artifact` → `9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8`
  - **Third-party, needs lookup:** `gvenzl/setup-oracle-free@v1` (in `actions/oracle_setup_ubuntu_runner`) is unpinned and its SHA isn't used elsewhere — look up the commit SHA for the `v1` release and pin it (`# v1`) in Task 1.2.
- **Behaviour is preserved** through Phases 0–3; only Phase 4 may change which build an axis uses, and every such change is validated individually (conclusion parity **and** "same tests actually ran").
- **Local composite actions run only after checkout** — a `uses: ./.github/actions/...` reference requires the repo already on disk, so no composite may perform the first checkout. Checkout stays as an inline step in each workflow.
- Reusable-workflow nesting ≤ 4 levels; the daily/cron path is already at 4.

## Review Focus

- **Repo/ref fallback across fork vs upstream.** Every consumer resolves `${{ inputs.pull_request_repo || github.repository }}` and `${{ inputs.pull_request_ref || github.ref }}`; after refactor an empty `pull_request_repo` must still check out the right repo. → pinned in Task 2.2 (dispatch on fork with `pull_request_repo` unset).
- **Composite-after-checkout ordering.** A local composite invoked before checkout fails to resolve. → pinned in Task 2.2 (every composite consumer keeps checkout as step 1 before any `./.github/actions/*`).
- **Empty / `{}` environment_variables.** `load-env-vars` must no-op cleanly (the original step was `if:`-guarded). → pinned in Task 2.1 (dispatch an axis with no custom env vars; assert no failure and no spurious exports).
- **DB2 teardown on failure/cancel.** The `docker stop/rm db2server` step runs `if: always()`; after refactor it must still fire so runners don't leak containers. → pinned in Task 2.5 (keep teardown caller-side with `if: always()`; verify on a db2 run).
- **Narayana m2 artifact-name reconstruction (Phase 3).** The m2 name embeds the Maven version; a wrong reconstruction makes `download-artifact` fail. → pinned in Task 3.1 (consumer resolves the exact name; verify CORE/RTS/XTS/JACOCO download succeeds).
- **Phase-2 behaviour-preservation deltas.** Two silent changes to avoid: `jacoco-qa-test` passes an *empty* `target_db` to Postgres today (it defines no job-level `TARGET_DB`), so `setup-database` must not let its `'jbossts'` default flip it (→ Task 2.5); and `as_tests`'s failure-log upload sets *no* `retention-days` today (90-day default), so adopting `upload-failure-logs` (default 30) must pass `retention_days: '90'` to preserve it (→ Task 2.4).

---

## Phase 0 — Tooling + baseline

### Task 0.1: Add actionlint + pinned-action guard

**Files:**
- Create: `.github/workflows/lint_actions.yml`

**Interfaces:**
- Produces: a `lint_actions` workflow that runs on `pull_request` (paths `.github/**`) and `workflow_dispatch`. It fails on any actionlint error. The unpinned-`uses:` guard lands in **report-only** mode here (annotates, exits 0) and is flipped to enforcing (exits non-zero) at the end of Task 1.2, once the known `experimental.yml` violations are fixed.

- [ ] **Step 1: Write the check** — add `lint_actions.yml` running `rhysd/actionlint` (pinned by SHA) over `.github/workflows/`, plus a grep step that fails if any `uses:` in `.github/**` references a floating tag (`@v\d`, `@main`, `@master`) instead of a 40-hex SHA. Allow `uses: ./` local actions.
- [ ] **Step 2: Verify it fails on current code** — Run locally or via dispatch: the grep guard over `.github/**`. Expected: it flags **all three** floating-tag sites — `experimental.yml`, `daily_job_experimental.yml`, and `gvenzl/setup-oracle-free@v1` in `actions/oracle_setup_ubuntu_runner` (the Phase-1 targets). This proves the guard works.
- [ ] **Step 3: Scope the guard so Phase 0 itself is green** — the lint workflow must pass for its own PR; the grep guard is expected to report the known `experimental.yml` violations, so land the guard in **report-only** mode here (annotate, exit 0) and flip it to **enforcing** (exit 1) at the end of Task 1.2 once the violations are fixed.
- [ ] **Step 4: Verify the lint workflow runs green** — Run: dispatch `lint_actions` on the fork. Expected: PASS (actionlint clean except the known floating tags, which are annotations only in report-only mode).
- [ ] **Step 5: Commit** — `git add .github/workflows/lint_actions.yml && git commit -m "ci: add actionlint and pinned-action guard (report-only)"`

### Task 0.2: Capture the instrumented baseline

**Files:**
- No committed file. The baseline is recorded in the Phase-0 PR description (conclusions, durations, test-report counts) as reference material, not committed to the repo.

**Interfaces:**
- Produces: a recorded baseline — per axis: conclusion, duration, and **test-report counts** (surefire/failsafe counts, QA `testoutput` tallies) — that every later phase compares against. This is the oracle for Phase-4's "same tests actually ran" check. Captured as **one full instrumented run up front** (decision: simplest complete oracle; the up-front spend is accepted).

- [ ] **Step 1: Temporarily instrument report retention** — on a throwaway branch, change the relevant log/report uploads so surefire/failsafe/`qa/testoutput` are uploaded **unconditionally** (not only `if: failure()`) with `retention-days` long enough to read (e.g. 7). This branch is NOT merged — it exists only to capture counts.
- [ ] **Step 2: Dispatch the full baseline** — dispatch `axes_selector` on the fork with every axis enabled, JDK 25, full DB set. Expected: all axes green (confirmed achievable on the fork).
- [ ] **Step 3: Record the baseline** — for each axis job, record conclusion, duration, and the test-report counts from the uploaded artifacts into the Phase-0 PR description.
- [ ] **Step 4: Discard the instrumentation branch** — do not merge it. The main line keeps the existing `if: failure()` upload behaviour.
- [ ] **Step 5: Commit** — nothing to commit on the main line (baseline is reference material). Note the baseline location in the Phase-0 PR description.

---

## Phase 1 — Hygiene (behaviour-preserving)

### Task 1.1: SHA-pin the floating first-party actions

**Files:**
- Modify: `.github/workflows/experimental.yml` (`actions/checkout@v7`, `actions/github-script@v9`, `actions/upload-artifact@v7`) and `.github/workflows/daily_job_experimental.yml` (`actions/checkout@v7`) — the first-party floating tags, pinned to the canonical SHAs from Global Constraints. (The third-party Oracle action is handled in Task 1.2.)

- [ ] **Step 1: Pin** — replace each floating tag with the canonical SHA from Global Constraints (keep the `# vX` comment).
- [ ] **Step 2: Verify actionlint** — Run: `actionlint .github/workflows/experimental.yml`. Expected: PASS, no floating-tag annotations for this file.
- [ ] **Step 3: Verify parity** — dispatch the EXPERIMENTAL axis on the fork (JDK 25). Expected: conclusion matches baseline.
- [ ] **Step 4: Commit** — `git commit -am "ci: SHA-pin actions in experimental.yml"`

### Task 1.2: Sweep remaining floating tags, fix names, enforce the guard

**Files:**
- Modify: any workflow or composite action still using a floating tag — notably `.github/actions/oracle_setup_ubuntu_runner/action.yml` (`gvenzl/setup-oracle-free@v1`, third-party); `.github/workflows/db_tests.yml` (`name: db_tests.yml` → `name: db_tests`); audit other `name:` fields for consistency
- Modify: `.github/workflows/lint_actions.yml` (flip guard from report-only to enforcing)

- [ ] **Step 1: Sweep and pin** — grep `.github/**` for floating `uses:` tags; pin each to a SHA. For the third-party `gvenzl/setup-oracle-free@v1`, look up the commit SHA of its `v1` release (GitHub releases/API) and pin it with a trailing `# v1`. Fix the `db_tests` name and any other extension-carrying or inconsistent `name:` values.
- [ ] **Step 2: Enforce the guard** — change `lint_actions.yml`'s grep step to exit non-zero on any floating tag.
- [ ] **Step 3: Verify** — Run: dispatch `lint_actions`. Expected: PASS with the guard now enforcing (no violations remain).
- [ ] **Step 4: Verify parity** — dispatch DB_TESTS with the **Oracle** database (JDK 25) on the fork, to exercise the newly pinned Oracle action and the renamed workflow. Expected: conclusion matches baseline.
- [ ] **Step 5: Commit** — `git commit -am "ci: pin remaining actions, normalise names, enforce pinned-action guard"`

---

## Phase 2 — Composite actions (behaviour-preserving)

Each task: create one composite, roll it out to all consumers, actionlint, one fork dispatch of the affected axes, commit. Checkout stays inline in every workflow (composites run after it).

### Task 2.1: `load-env-vars` composite

**Files:**
- Create: `.github/actions/load-env-vars/action.yml`
- Modify: `as_builder.yml`, `as_tests.yml`, `experimental.yml`, `quickstart.yml`, and `performance.yml`'s **`build-narayana` job only** (replace the inline github-script "Load Dynamic Environment Variables" step). NOT `performance.yml`'s `benchmarks` job — it has no `actions/checkout` (it `git clone`s the perf repo), so a local `uses: ./.github/actions/...` can't resolve there; that one stays inline.

**Interfaces:**
- Produces: composite action `load-env-vars` with input `environment_variables` (string, default `'{}'`). Parses the JSON and `core.exportVariable`s each pair. No-ops cleanly on `''` or `'{}'` (guard inside the action, since a composite can't carry the caller's step-level `if:`).

- [ ] **Step 1: Write the composite** — `action.yml` using `actions/github-script` (pinned) that early-returns when the input is empty/`{}`, else exports each key.
- [ ] **Step 2: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 3: Roll out** — replace the inline step in each consumer with `uses: ./.github/actions/load-env-vars` placed **after checkout**, passing `environment_variables: ${{ inputs.environment_variables }}`. Note `as_builder` and `performance`'s `build-narayana` currently place the env-load *before* checkout — moving it after is behaviour-safe (the exported vars apply to later steps regardless). Leave `performance`'s `benchmarks` job inline (no checkout there). In `build-narayana`, place the composite after checkout but **before** the `Build SPI branch` step, which reads `env.SPI_BRANCH`.
- [ ] **Step 4: Verify parity + empty-env no-op** — dispatch AS_TESTS on the fork twice: once with a custom env var (e.g. `SPI_BRANCH` via dispatch inputs) and once with none. Expected: both match baseline; the no-env run shows no failure and no spurious exports (Review Focus: empty env).
- [ ] **Step 5: Commit** — `git commit -am "ci: extract load-env-vars composite action"`

### Task 2.2: `prepare-build-env` composite

**Files:**
- Create: `.github/actions/prepare-build-env/action.yml`
- Modify: `core.yml`, `rts.yml`, `xts.yml`, `jacoco.yml`, `as_builder.yml`, `as_tests.yml`, `qa_jta.yml`, `qa_jts_openjdkorb.yml`, `db_tests.yml` (replace the repeated setup-java + purge-m2 + git-identity steps). `quickstart.yml` and `performance.yml` opt out or partially use it (see note).

**Interfaces:**
- Produces: composite action `prepare-build-env` with inputs `jdk_version` (required), `purge_m2` (default `'true'`), `cache_dependency_path` (optional). Steps: setup-java (temurin, `jdk_version`, `cache: maven`, optional `cache-dependency-path`) + purge `~/.m2/repository/org/jboss/narayana` and `~/.m2/repository/org/wildfly` when `purge_m2` is true + configure git identity (`github-actions[bot]`).
- Consumes: nothing; **must be invoked after the inline checkout step** (Review Focus: composite-after-checkout).
- Note: this composite does NOT checkout (chicken/egg with local actions). `quickstart.yml` uses a different `cache-dependency-path` and does not purge narayana/wildfly the same way — pass `cache_dependency_path` and `purge_m2: 'false'`, or leave quickstart's setup inline if the fit is poor (builder's call).

- [ ] **Step 1: Write the composite** — `action.yml` as specified.
- [ ] **Step 2: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 3: Roll out** — in each consumer, keep checkout as step 1, then replace the setup-java and purge-m2 steps with `uses: ./.github/actions/prepare-build-env` (passing `jdk_version`). Note the git identity is currently set *inline* at the top of each axis's test `run:` block, not as a standalone step — remove those two `git config` lines from the test step, since the composite now sets the global identity earlier in the job (`git config --global` persists across subsequent steps). Note `jacoco.yml` has three jobs (`jacoco-test`, `jacoco-qa-test`, `code-coverage-report`) — apply the composite to each separately.
- [ ] **Step 4: Verify parity + repo/ref fallback** — dispatch CORE and QA_JTA on the fork (JDK 25, single DB), once with `pull_request_repo` unset. Expected: conclusions match baseline; checkout resolves the fork repo correctly (Review Focus: repo/ref fallback, composite ordering).
- [ ] **Step 5: Commit** — `git commit -am "ci: extract prepare-build-env composite action"`

### Task 2.3: `restore-narayana-build` composite

**Files:**
- Create: `.github/actions/restore-narayana-build/action.yml`
- Modify: `core.yml`, `rts.yml`, `xts.yml`, `jacoco.yml` (the download-m2 + download-zip + unzip block). Consumers: `core`/`rts`/`xts` and `jacoco-test` download m2 + WildFly zip; `jacoco-qa-test` downloads **m2 only**. **NOT** `jacoco.yml`'s `code-coverage-report` job — it downloads `coverage-data-*` by `pattern:` + `merge-multiple`, a different mechanism this composite (one artifact by `name:`) cannot express; leave it unchanged.

**Interfaces:**
- Produces: composite action `restore-narayana-build` with inputs `narayana_artifact_name` (required) and `jboss_as_zip_name` (optional, default `''`). Downloads the Narayana m2 artifact into `~/.m2/repository/org/jboss/narayana`; when `jboss_as_zip_name` is non-empty, downloads it and unzips into `./wildfly` (reproducing the `unzip … && mv "$dir" wildfly` logic). Uses pinned `download-artifact`.
- Note: RTS/XTS currently **download these artifacts but rebuild anyway** (their `PROFILE=RTS|XTS` preset forces `AS_BUILD=1 NARAYANA_BUILD=1`), so adopting the composite there preserves the existing (wasteful) download. Keep them as consumers for behaviour-preservation; making them *true* consumers is a Phase-4 reuse item (Task 4.2), not this task.

- [ ] **Step 1: Write the composite** — handle the m2-only case (skip WildFly steps when `jboss_as_zip_name` empty).
- [ ] **Step 2: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 3: Roll out** — replace the blocks in the consumers; pass `jboss_as_zip_name` only where WildFly is needed (CORE/RTS/XTS/jacoco-test), omit for jacoco-qa-test (m2 only). Leave `code-coverage-report` untouched (pattern-based `coverage-data-*` download, not the Narayana m2).
- [ ] **Step 4: Verify parity** — dispatch CORE, RTS, XTS, JACOCO on the fork (JDK 25). Expected: artifacts download/unzip; conclusions match baseline.
- [ ] **Step 5: Commit** — `git commit -am "ci: extract restore-narayana-build composite action"`

### Task 2.4: `upload-failure-logs` composite

**Files:**
- Create: `.github/actions/upload-failure-logs/action.yml`
- Modify: only the axes that share the common log glob — the failure-log upload steps in `core.yml`, `rts.yml`, `xts.yml`, `as_tests.yml`, and `jacoco.yml`'s non-QA jobs (`jacoco-test`, `code-coverage-report`). (Decision: scope to where it removes real duplication.) QA axes (`qa/testoutput`), `performance` (`performance/**`), and `quickstart` (`quickstart/**`) keep their uploads **inline** — they'd only override `path` and gain little.

**Interfaces:**
- Produces: composite action `upload-failure-logs` with inputs `name` (required), `path` (optional, default the common glob: `**/*.*log*`, `**/ObjectStore*/**`, `**/tx-object-store/**`, `**/*tx-object-store.zip`, `**/target/*surefire-reports*/**`, `**/target/*failsafe-reports*/**`), `retention_days` (default `'30'`), `if_no_files_found` (default `'warn'`). Wraps pinned `upload-artifact`.
- Note: the caller keeps its step-level `if:` guard (`failure()`/`cancelled()` on the axis step) — a composite can't read the caller's `steps.*.conclusion`.
- **Behaviour-preservation (retention):** `as_tests`'s current failure upload sets no `retention-days` (90-day default), while the composite defaults to `30` — so the `as_tests` call must pass `retention_days: '90'` to avoid a silent 90→30 change. The other in-scope axes already use 30, so they need no override.

- [ ] **Step 1: Write the composite** — default path = common glob; `path` override still accepted for flexibility, though in-scope callers rely on the default.
- [ ] **Step 2: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 3: Roll out** — in the common-glob axes only, replace the failure-log upload step with the composite, preserving each caller's `if:` condition and passing `name` (the default glob needs no `path` override here).
- [ ] **Step 4: Verify parity** — dispatch one adopting axis (RTS), forcing a failure path if feasible, else confirm green runs are unaffected. Expected: conclusion matches baseline; on an induced failure the expected log artifact appears with the common glob.
- [ ] **Step 5: Commit** — `git commit -am "ci: extract upload-failure-logs composite action"`

### Task 2.5: `setup-database` composite

**Files:**
- Create: `.github/actions/setup-database/action.yml`
- Modify: `db_tests.yml` (both jobs), `qa_jta.yml`, `jacoco.yml` (jacoco-qa-test) — replace the JDBC-string bash block and the four-way DB `if`-dispatch; also **add** a DB2 teardown step to `jacoco-qa-test` for consistency

**Interfaces:**
- Produces: composite action `setup-database` with inputs `database` (required: `postgres|mysql|oracle|db2`) and `target_db` (default `'jbossts'`); output `jdbc_access` (the full `JDBC_ACCESS;DB_DETAILS` string computed for the database). Steps: compute the JDBC strings (the shared bash block) → set the `jdbc_access` output; then dispatch to the matching existing `*_setup_ubuntu_runner` composite via internal `if: ${{ inputs.database == '…' }}` steps.
- Note: **DB2 teardown stays caller-side** (`docker stop/rm db2server` with `if: always() && matrix.database == 'db2'`) — a composite can't register an always-run post step for the caller (Review Focus: DB2 teardown). The `dbjbossts-properties.xml` templating stays in the caller (`db_tests` only), consuming `steps.<id>.outputs.jdbc_access`.
- Note: this is **not a uniform 1:1 swap** across consumers. `db_tests` (both jobs) and `qa_jta` set up one database per matrix leg (one `setup-database` call; `db_tests` reads `jdbc_access`, `qa_jta` ignores it). `jacoco.yml`'s `jacoco-qa-test` brings up **all four databases at once** for `junit-jdbc-testsuite` (not per matrix leg), so there it calls `setup-database` **four times** (once per database).
- **Behaviour-preservation (`target_db`):** only the Postgres composite consumes `target_db`. `db_tests`/`qa_jta` set job-level `TARGET_DB: jbossts` and pass it; **`jacoco-qa-test` sets no `TARGET_DB`**, so today it passes an *empty* `target_db` to Postgres. `setup-database` defaults `target_db` to `'jbossts'`, so when `jacoco-qa-test` adopts it, it must **explicitly pass the empty value** (matching `env.TARGET_DB`) to avoid an unflagged `'' → 'jbossts'` change. (If `'jbossts'` is actually desired there, make it a deliberate, separately-noted change — not a silent default.) Nuance: the existing postgres composite *also* defaults `target_db` to `'jbossts'`, so today's effective value depends on whether GHA applies an input default when the caller passes an explicit empty string — confirm on the fork (behaviour-preserving either way, so not a blocker).
- **Intentional consistency change:** `jacoco-qa-test` has **no** DB2 teardown today; this task **adds** one — `docker stop/rm db2server` with `if: ${{ always() && matrix.qa_target == 'junit-jdbc-testsuite' }}` — so all three DB-using jobs tear DB2 down alike. This is a deliberate, cosmetic, caller-side behaviour *addition* (harmless on ephemeral runners; insurance against runner reuse, matching the existing `--restart=always` DB2 container) — the one intended deviation from Phase 2's otherwise strict behaviour-preservation.

- [ ] **Step 1: Write the composite** — JDBC computation (all four branches) → output; DB-setup dispatch to the four existing composites. Consumers that don't need `jdbc_access` simply ignore the output.
- [ ] **Step 2: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 3: Roll out** — replace the duplicated blocks in `db_tests` (both jobs), `qa_jta`, and `jacoco-qa-test` (four calls there); keep the existing DB2 teardowns and (in db_tests) the properties templating caller-side, and **add** the DB2 teardown step to `jacoco-qa-test`.
- [ ] **Step 4: Verify parity + DB2 teardown** — dispatch DB_TESTS on the fork across all four databases (JDK 25). Expected: conclusions match baseline; the db2 leg's teardown runs; JDBC-dependent tests behave identically. Additionally dispatch JACOCO (JDK 25) and confirm the new `jacoco-qa-test` DB2 teardown runs on the `junit-jdbc-testsuite` leg.
- [ ] **Step 5: Commit** — `git commit -am "ci: extract setup-database composite action"`

---

## Phase 3 — Flatten nesting (behaviour-preserving)

### Task 3.1: Split the builder and dispatch CORE/RTS/XTS/JACOCO from axes_selector

**Files:**
- Modify: `.github/workflows/as_builder.yml` (remove the internal `CORE/RTS/XTS/JACOCO` dispatch jobs; keep only the build-and-upload job plus its outputs)
- Modify: `.github/workflows/axes_selector.yml` (add top-level `CORE/RTS/XTS/JACOCO` jobs with `needs: as_builder`, dispatching the respective reusable workflows)
- Possibly modify: `core.yml`, `rts.yml`, `xts.yml`, `jacoco.yml` artifact-name inputs if the reconstruction strategy changes the name

**Interfaces:**
- Consumes: the non-artifact selector outputs (`pr_ref`, `pr_repo`, axis booleans, `jdk_versions`). The builder runs as a **per-JDK matrix**, so its per-leg artifact-name outputs cannot be selected by a downstream matrix job — artifact names are therefore **reconstructed**, not consumed as outputs.
- Produces: in `axes_selector`, four new jobs `CORE/RTS/XTS/JACOCO`, each `needs: [axes_selector, as_builder]`, matrixed over `jdk_versions`, gated on the matching axis output, dispatching `core.yml`/`rts.yml`/`xts.yml`/`jacoco.yml` with the reconstructed artifact names.
- Decision (Review Focus: m2 name reconstruction): reconstruction from `run_id` + jdk is **mandatory** here (matrix outputs can't be selected downstream). The WildFly zip name (`${run_id}-jboss-as-zip-jdk${jdk}`) already reconstructs cleanly, but the Narayana m2 name embeds the Maven version — so **drop the version from the m2 artifact name** in the builder (behaviour-safe rename, producer and consumer updated together) so both names reconstruct from `run_id` + jdk alone.
- Net effect: all axes become siblings under `axes_selector`; PR/dispatch nesting drops to 2 levels and the daily/cron path from 4 to 3.

- [ ] **Step 1: Make `as_builder` a pure builder** — remove the `CORE/RTS/XTS/JACOCO` jobs; retain the build-and-upload job, and drop the Maven version from the m2 artifact name so it reconstructs from `run_id` + jdk.
- [ ] **Step 2: Add top-level axis jobs in `axes_selector`** — four jobs with `needs: as_builder`, matrixed over `jdk_versions`, each gated on its axis output, passing the reconstructed artifact names.
- [ ] **Step 3: Verify actionlint + graph** — Run: `actionlint`. Expected: PASS; no `needs` references a removed job.
- [ ] **Step 4: Verify parity** — dispatch `axes_selector` on the fork with CORE+RTS+XTS+JACOCO enabled (JDK 25). Expected: the builder runs once, all four consumers download artifacts and finish with baseline-matching conclusions. This exercises the 2-level dispatch topology; the daily/cron 4→3 reduction is structural (not separately triggered here). Note: a downstream axis matrixed over `jdk_versions` with `needs: as_builder` waits for *all* builder legs, not just the matching JDK — with multiple JDKs this changes gating/parallelism but not conclusions.
- [ ] **Step 5: Commit** — `git commit -am "ci: flatten axes_selector/as_builder nesting; dispatch build axes at top level"`

---

## Phase 4 — Build-reuse consistency (behaviour may change; gated, last)

Runs only after Phases 1–3 are green. Every reuse change is validated individually: conclusion parity **and** "same tests actually ran" against the Phase-0 baseline counts.

### Task 4.1: Unify the performance builder

**Files:**
- Modify: `.github/workflows/performance.yml` (replace the internal `build-narayana` job's bespoke build + upload with the canonical builder/artifact pattern, consumed via `restore-narayana-build`)

**Interfaces:**
- Consumes: the canonical build artifact (same mechanism as Phase 3) via `restore-narayana-build`.
- Note / risk to resolve: the perf builder uses `build.sh -Pcommunity -Dexperimental -DskipTests` (experimental reactor, no WildFly) whereas `as_builder` uses `narayana.sh` `build_narayana` with `-Pcommunity`, no `-Dexperimental`, plus WildFly. Before switching, confirm the unified artifact contains exactly what the benchmarks need (notably the `-Dexperimental` modules) — if it doesn't, either extend the canonical build or document that performance keeps a distinct builder.
- Note / `OVERRIDE_NARAYANA_VERSION`: the `benchmarks` step needs the Narayana version (today from `needs.build-narayana.outputs.narayana-version`). If performance is folded onto the per-JDK **matrix** builder, that output isn't selectable downstream (same limit as Phase-3 artifact names) — so the benchmark step must obtain the version another way (e.g. `mvn help:evaluate -Dexpression=project.version -q -DforceStdout` after `restore-narayana-build`, or reconstruct it). Resolve this as part of the unification.

- [ ] **Step 1: Investigate artifact adequacy** — compare the modules the benchmarks resolve against what the canonical artifact provides. Record the finding.
- [ ] **Step 2: Apply the unification (or document divergence)** — if adequate, replace the internal build with the canonical artifact + `restore-narayana-build`; else document why performance keeps its own builder and stop this task.
- [ ] **Step 3: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 4: Verify parity** — dispatch PERFORMANCE on the fork with a reduced benchmark subset (JDK 25). Expected: benchmarks resolve the Narayana version and run; conclusions match baseline.
- [ ] **Step 5: Commit** — `git commit -am "ci: unify performance build with canonical Narayana artifact"` (or `"docs: record why performance keeps a distinct builder"`).

### Task 4.2: Per-axis build-reuse audit

**Files:**
- Modify (conditionally, one axis at a time): `rts.yml`, `xts.yml`, `core.yml`, `as_tests.yml`, `qa_jta.yml`, `qa_jts_openjdkorb.yml`, `db_tests.yml`, `experimental.yml` — make each actually reuse the cached build **only where proven safe**. The mechanism differs per axis (it is *not* uniformly "drop `NARAYANA_BUILD=1`"): RTS/XTS must stop rebuilding — **prefer giving them explicit flags** (invocation-level) over editing the `narayana.sh` `PROFILE=RTS|XTS` preset, since rewriting the script's internals is a Non-goal; CORE/JACOCO already reuse WildFly and the open question is the Narayana rebuild / possibly-dead m2 download; AS_TESTS reuses WildFly; QA/DB use explicit `NARAYANA_BUILD=1`; `experimental` uses raw `mvn` (no `NARAYANA_BUILD`).

**Interfaces:**
- Consumes: the canonical build artifact via `restore-narayana-build`.
- Per-axis known subtleties: **RTS/XTS** are the biggest win — they already download the artifacts but rebuild because the `PROFILE=RTS|XTS` preset re-`export`s `AS_BUILD=1 NARAYANA_BUILD=1`; fixing that (or giving them explicit flags) makes the download count. `CORE`/`JACOCO` run Narayana surefire tests needing test-scoped compiled classes the published m2 may lack (Narayana rebuild may be unavoidable — confirm; also check whether their m2 download is dead weight). QA axes build with different profiles and the ant QA suite (reuse may be partial/infeasible). `experimental` runs raw `mvn install` (no `narayana.sh`) then the `experimental/` reactor — the question is whether its core build can come from the cached m2. Each axis is decided on evidence, not assumption.

- [ ] **Step 1: For each candidate axis, investigate** — determine whether the cached m2 supplies everything the axis needs. Record a per-axis decision (reuse / keep rebuild / partial) with its rationale.
- [ ] **Step 2: Apply one switchable axis** — convert a single axis to reuse; leave the others unchanged.
- [ ] **Step 3: Verify actionlint** — Run: `actionlint`. Expected: PASS.
- [ ] **Step 4: Verify parity + same-tests** — dispatch that axis on the fork (JDK 25). Expected: conclusion matches baseline **and** its test-report counts match the Phase-0 baseline counts (not merely green — guards the "passes but no longer exercises what it used to" anti-goal).
- [ ] **Step 5: Commit, then repeat Steps 2–5 for the next switchable axis** — `git commit -am "ci: reuse cached Narayana build for <axis>"`. Axes that must keep rebuilding get a one-line comment in their workflow stating why (no code change).

### Task 4.3 (optional): Document the narayana.sh invocation contract

**Files:**
- Modify: `.github/scripts/narayana.sh` (header comment only)

**Interfaces:**
- Produces: a header comment describing the env-var contract (`NARAYANA_BUILD`, `AS_BUILD`, `QA_TESTS`, `PROFILE`, …) and the two invocation styles.
- Caution: do NOT force every caller onto `PROFILE=` — the presets are stale relative to the cached-build model (`PROFILE=CORE` sets `AS_BUILD=1`, which would re-introduce a WildFly rebuild). Document; do not rewrite.

- [ ] **Step 1: Add the header comment** describing the contract and noting the stale-preset caution.
- [ ] **Step 2: Verify** — Run: `bash -n .github/scripts/narayana.sh`. Expected: PASS (no syntax change).
- [ ] **Step 3: Commit** — `git commit -am "docs: document narayana.sh env-var contract"`

---

## End-of-project confirmation

- [ ] After Task 4.2, dispatch `axes_selector` on the fork with **all axes enabled, all JDKs, full DB set** once. Expected: every axis green, conclusions and test-report counts consistent with the Phase-0 baseline (accounting for any deliberate Phase-4 reuse changes). This is the single fuller-matrix run reserved by the budget constraint.
