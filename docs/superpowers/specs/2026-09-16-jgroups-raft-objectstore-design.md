# A jgroups-raft Object Store for Narayana (JBTM-4038)

**Status:** Design approved, ready for implementation planning
**Date:** 2026-09-16
**Ticket:** JBTM-4038

## 1. Summary

Add a new Narayana `ObjectStore` implementation that persists transaction/recovery
state into a **replicated Raft log** using [jgroups-raft](https://github.com/jgroups-extras/jgroups-raft).
Each Narayana JVM embeds a Raft node; the Narayana instances form the Raft cluster and
replicate their object-store writes to a majority before a write returns. Because every
node holds every log, any surviving node can recover the **in-doubt** (prepared but not yet
completed) transactions of a node that has crashed — this delivers **high availability of
transaction logs**.

Recovery is gated so that, in the common case, **only the current Raft leader** runs the
recovery scan. During a leadership change a demoted-but-unaware leader can briefly overlap;
that window is bounded by JGroups failure detection and kept safe by Raft write-fencing,
linearizable-read fencing, and Narayana's idempotent recovery (see §8 and §10).

## 2. Goals and non-goals

### Goals
- A drop-in `ObjectStoreAPI` backend selectable purely by configuration
  (`objectStoreType`), no changes to Narayana's store plug-in mechanism.
- Replicate transaction logs (the **action store**) across an embedded Raft cluster so a
  surviving node can complete/roll back a crashed node's in-doubt transactions.
- Serve **all three** Narayana store roles (action, state, communication) from the Raft
  backend.
- Single-recoverer, idempotent HA recovery via **leader-only recovery** (no double recovery under
  stable leadership; the rare leadership-transition window is made safe — see §8.1).

### Non-goals (v1)
- Dynamic cluster membership at runtime (designed for, not built — see §12).
- Out-of-process recovery managers (the leadership→recovery signal is in-JVM; the embedded
  WildFly case is supported).
- The full shadow / `hide_state` / `reveal_state` protocol (the store is committed-only;
  see §4 and §6).
- `AsyncSnapshot` (non-blocking snapshotting) — a later optimization.

## 3. Background

### 3.1 Narayana object store (integration surface)
- A store implements `com.arjuna.ats.arjuna.objectstore.ObjectStoreAPI` (the union of
  `ParticipantStore` + `RecoveryStore`, both extending `TxLog` / `BaseStore`).
- It is selected by class name via the property
  `com.arjuna.ats.arjuna.objectstore.objectStoreType` (with `stateStore.` and
  `communicationStore.` prefixed variants for the other two roles).
- `com.arjuna.ats.arjuna.objectstore.StoreManager` instantiates it through
  `ClassloadingUtility.loadAndInstantiateClass(...)`, which prefers a **single-arg
  constructor whose parameter type name ends in `EnvironmentBean`** (auto-populated from
  properties by `BeanPopulator`), else a public no-arg constructor.
- `StoreManager` hands out three logically distinct stores:
  - **action store** (default/unnamed) — `getRecoveryStore()` / `getTxLog()` /
    `getParticipantStore()`. Holds transaction logs (an `AtomicAction`'s participant list
    and intentions). This is the store crash recovery reads.
  - **state store** (`stateStore`) — used by `StateManager` (the TXOJ / STM programming
    model, `LockManager`, and `getEISNameStore()`). Holds application-level recoverable
    object state. Typically unused by plain JTA/XA.
  - **communication store** (`communicationStore`) — holds `TransactionStatusManagerItem`
    records: the host/port where a live TM process listens, so recovery can ask "is
    transaction X still in progress?" before recovering it.
- Records are keyed by `(Uid, typeName)`. `type()` is a hierarchical slash-delimited
  string (e.g. `/StateManager/AtomicAction`). `OutputObjectState`/`InputObjectState` carry
  the serialized bytes.

### 3.2 Store operations recovery actually uses (verified against source)
The JTA crash-recovery path (`PeriodicRecovery` → `AtomicActionRecoveryModule`,
`XARecoveryModule`, `TransactionStatusConnectionManager`) uses **only**:
`allObjUids`, `allTypes`, `currentState`, `read_committed`, `remove_committed`, and
`write_committed` (on rewrite). It never calls `write_uncommitted`, `commit_state`,
`read_uncommitted`, `remove_uncommitted`, `hide_state`, `reveal_state`, or `isType`.
`hide_state`/`reveal_state` are used only by the OSB/JMX admin tooling, not recovery.

### 3.3 fullCommitNeeded() and committed-only stores (verified)
- For the **action, recovery, TxLog, and communication** roles a committed-only store works
  **unconditionally**: `BasicAction` and `TransactionStatusManagerItem` only ever use
  `write_committed`/`read_committed`/`remove_committed`/`allObjUids`/`allTypes`/`currentState`
  and do not even consult `fullCommitNeeded()`.
- For the **state store** (arbitrary `StateManager`/TXOJ objects via `PersistenceRecord`),
  the committed-only path is additionally gated by
  `com.arjuna.ats.arjuna.coordinator.transactionLog.writeOptimisation=true` (with
  `classicPrepare=false`, the default). Otherwise `topLevelPrepare()` falls through to
  `StateManager.deactivate(store, false)` → `write_uncommitted`, which a committed-only store
  rejects. **Pure JTA is unaffected** (participant records are folded into the `AtomicAction`
  intention list and written with a single `write_committed`).

### 3.4 jgroups-raft (programming model)
- **`org.jgroups.raft.StateMachine`**: `byte[] apply(byte[] data, int offset, int length,
  boolean serialize_response)`, `void readContentFrom(DataInput in)`,
  `void writeContentTo(DataOutput out)`. `apply` is invoked only after a log entry reaches
  consensus, **sequentially on a single event-loop thread**, and must be deterministic and
  never throw.
- **`org.jgroups.raft.RaftHandle`**: `new RaftHandle(JChannel, StateMachine)`;
  `setAsync(byte[],off,len,Options)` (state-changing, logged),
  `getAsync(...)` (linearizable read, not logged), `isLeader()`, `leader()`,
  `addRoleListener(RAFT.RoleChange)`, `lastApplied()`, `commitIndex()`, `snapshot()`.
- **Logs**: `FileBasedLog` (default, on-disk, per node, fsync-able), `InMemoryLog` (tests).
- **Config**: JGroups XML stack with `raft.ELECTION` / `raft.RAFT` / `raft.REDIRECT`;
  key attributes `raft_id`, `members`, `log_class`, `log_dir`. Membership majority is
  computed from the static `members` list; runtime changes via `addServer`/`removeServer`.
- **Snapshots**: triggered manually via `RaftHandle.snapshot()` or automatically at the
  `max_log_size` threshold; transferred to lagging nodes via InstallSnapshot.
- Coordinates: `org.jgroups:jgroups-raft:2.0.0.Final-SNAPSHOT` on
  `org.jgroups:jgroups:5.5.7.Final`. Minimum Java 17.
- **Leadership loss / step-down (verified in source):** jgroups-raft has **no Raft-level
  check-quorum timer and no leader lease**. A leader demotes to follower only on (a) receiving a
  higher term, or (b) a JGroups **view change** that drops it below majority
  (`ELECTION.handleView` → `Majority.lost` → `setLeaderAndTerm(null)` → `changeRole(Follower)`).
  So a partitioned leader keeps believing it leads until the underlying JGroups
  failure-detection/GMS layer installs a new view — the demotion latency is a property of the
  JGroups stack (FD/GMS), not a Raft knob. `RAFT.RoleChange` fires on both demotion paths.
- **`getAsync` is ReadIndex-fenced (verified):** a linearizable read confirms leadership via a
  majority AppendEntries round-trip before completing. A minority/zombie leader cannot return a
  stale value — the read blocks and then fails with `notCurrentLeader()`. `setAsync` writes are
  likewise majority-fenced. (Direct, non-Raft reads of a node's local applied map are **not**
  fenced — see §7.6.)

## 4. Design decisions (with rationale)

| Decision | Choice | Rationale |
|---|---|---|
| Primary goal | HA of transaction logs | The recovery use case: a surviving node completes a crashed node's in-doubt (prepared but not yet completed) transactions. |
| Topology | Embedded peers — Narayana JVMs *are* the Raft cluster | No external service; logs replicate directly among app nodes. |
| Recovery coordination | Leader-only recovery, in scope | Raft gives exactly one leader; gate Narayana recovery to it. Single recoverer in the common case; a bounded demoted-unaware-leader window is kept safe by write/read fencing + idempotent recovery (§8.1, §10). |
| Store roles served | All three (action, state, communication) | General `ObjectStoreAPI` wired via the three `objectStoreType` variants. |
| Membership | Static list now; structured for dynamic later | Keeps v1 tractable; a stable majority (3/5) is predictable. |
| Architecture | Direct `ObjectStoreAPI` + purpose-built Raft `StateMachine`, committed-only | Cleanest 1:1 fit with Raft; no fixed-capacity impedance mismatch (unlike reusing `SlotStore`); full control of enumeration & snapshots. |
| Store fidelity | Committed-only (`fullCommitNeeded()==false`; shadow/hide/reveal throw) | Matches the exact subset recovery uses (§3.2/§3.3); mirrors `SlotStoreAdaptor`/`InfinispanSlots` prior art. State-store role documents the `writeOptimisation=true` requirement. |
| Read consistency | Linearizable reads by default (`getAsync`); `localApplied` opt-in | Linearizable reads are leadership-fenced — correct for the state store (reads on any node) and they fence a zombie leader's recovery; `localApplied` trades that for lower latency on the recovery-only (action/communication) roles (§7.6). |
| Raft runtime sharing | One shared Raft group (channel + StateMachine) per JVM, shared by the ≤3 store instances | One Raft group ⇒ exactly one leader per cluster, so every node has an unambiguous leader/non-leader status for recovery gating (vs. three separate groups, where a node could lead one role's group but follow another's); plus one replicated log and less overhead. |
| Packaging | New standalone Maven module | Keeps the `2.0.0-SNAPSHOT` jgroups-raft dependency off core `arjuna`. |

Approaches considered and rejected:
- **Reuse `BackingSlots`/`SlotStore`/`SlotStoreAdaptor`** (mirror `InfinispanSlots`): less new
  store code, but `SlotStore` is a fixed-size slot array whose index is rebuilt by scanning —
  an impedance mismatch with Raft, which already provides a replicated map, plus artificial
  capacity limits.
- **Reuse jgroups-raft `ReplicatedStateMachine<K,V>`**: its map is private and offers no
  type-based enumeration, so `allObjUids`/`allTypes` (required by recovery) cannot be
  implemented without forking it.

## 5. Architecture

- New Maven module `ArjunaCore/jgroups-raft-objectstore` (artifact `jgroups-raft-objectstore`),
  depending on `arjuna` and `org.jgroups:jgroups-raft`. Java 17.
- Package `com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft`.

```
 Narayana JVM A (raft_id=A)      Narayana JVM B (=B)        Narayana JVM C (=C)
 ┌─────────────────────────┐    ┌───────────────────┐     ┌───────────────────┐
 │ StoreManager            │    │ ...               │     │ ...               │
 │  action/state/comm      │    │                   │     │                   │
 │  RaftObjectStore ×3 ────┼─┐  │                   │     │                   │
 │   └─ RaftStoreRuntime ──┼─┼──► one JChannel + RAFT + StateMachine per JVM   │
 │        (shared)         │ │  │                   │     │                   │
 │  FileBasedLog (local)   │ │  │  FileBasedLog     │     │  FileBasedLog     │
 └─────────────────────────┘ │  └───────────────────┘     └───────────────────┘
        └──────── Raft replication (majority commit) ─────────┘
```

## 6. Components

All in `com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft`.

| Class | Responsibility |
|---|---|
| `RaftObjectStore implements ObjectStoreAPI` | Narayana-facing adaptor. Committed-only: implements `write_committed`, `read_committed`, `remove_committed`, `allObjUids` (both arities), `allTypes`, `currentState`, `sync` (no-op), `getStoreName`, `start`, `stop`; `fullCommitNeeded()` → `false`. `write_uncommitted`, `read_uncommitted`, `remove_uncommitted`, `commit_state`, `hide_state`, `reveal_state`, `isType` throw `ObjectStoreException("not supported")` (same stance as `SlotStoreAdaptor`). Carries a **partition id** (store role) and a reference to the shared runtime. |
| `RaftObjectStoreEnvironmentBean` | `@PropertyPrefix("com.arjuna.ats.arjuna.objectstore.jgroupsraft.")` config bean, auto-populated by `BeanPopulator`. See §9. |
| `RaftStoreRuntime` | Per-JVM singleton keyed by cluster name. Owns the `JChannel` + `RaftHandle` + `ObjectStoreStateMachine`, ref-counted across the ≤3 store instances (last `stop()` closes the channel). Registers the leadership listener on connect. Exposes `write`/`remove`, `read`, `scanUids`, `scanTypes`, `currentState`. |
| `ObjectStoreStateMachine implements StateMachine` | Replicated state + command applier + snapshot serializer. `apply()` handles both mutating commands (`WRITE`/`REMOVE`, via `setAsync`) and read-only query commands (`READ`/`SCAN_UIDS`/`SCAN_TYPES`/`CURRENT_STATE`, via `getAsync`); read-only commands must never mutate state or replicas diverge. |
| `StoreCommand` (codec) | Serialize/deserialize all commands — mutating `WRITE`/`REMOVE` and read-only `READ`/`SCAN_UIDS`/`SCAN_TYPES`/`CURRENT_STATE` — to/from the `byte[]` payload. |
| `RaftRecoveryLeadershipListener implements RAFT.RoleChange` | Bridges Raft leadership → `RecoveryManager` suspend/resume (see §8). |

## 7. Data model, wire format & data flow

### 7.1 Key and in-memory state
A record is identified by `(partition, typeName, Uid)`:
- `partition` — the store role (`defaultStore` / `stateStore` / `communicationStore`), so the
  three roles never collide and scans are naturally scoped to a role. It is set by the `partition`
  config property on each role's bean — the constructor receives only the `EnvironmentBean`, not
  the `StoreManager` instance name, so the role cannot be inferred implicitly. `RaftStoreRuntime`
  **fails fast** if two stores sharing a runtime declare the same partition (which would merge
  their key-spaces). If a future `BeanPopulator` exposes the instance name to the bean, `partition`
  can default to it with the property as an override.
- `typeName` — Narayana's `type()` string (the scan axis for `allObjUids(type,…)`).
- `Uid` — `Uid.stringForm()`.

State machine state: `Map<partition, Map<typeName, Map<uidString, byte[]>>>`. Nested maps give
O(1) `allObjUids` by type and cheap `allTypes`.

### 7.2 The ledger vs the map (single source of truth)
There are two per-node stores, and the relationship between them is the core of the design:
- The **Raft log** (`FileBasedLog`, on disk) is the **single source of truth** — the durable,
  totally-ordered ledger of `WRITE`/`REMOVE` commands, managed entirely by jgroups-raft.
- The **in-memory map** is a **derived materialized view** — the deterministic fold of the
  committed log. We **never** mutate it directly; the *only* way data enters the map is Raft
  calling `apply()` after consensus. There is no dual-write and no manual reconciliation: one
  write path (the log), and the map is a pure projection of it. On restart the map is rebuilt
  from the latest snapshot (`readContentFrom`) plus replay of post-snapshot entries (`apply`);
  it is never persisted separately.

### 7.3 Concurrency
All mutations happen on the single Raft event-loop thread via `apply()`, so the write path needs
**no locking** — there is never write-write contention. Read threading depends on `readMode`:
- **`linearizable`** — per-record reads (`read_committed`, `currentState`) run through `apply()`
  on the event-loop thread, serialized with writes (no concurrency hazard). **Scans**
  (`allObjUids`, `allTypes`) are *not* executed wholesale on that thread — a large scan would
  stall consensus — but issue a lightweight `getAsync` leadership/read-index **barrier** and then
  iterate the local applied map **off-loop** (see §7.6).
- **`localApplied`** — all reads run directly on the calling Narayana thread against the local
  map (off-loop): the single-writer / multiple-readers case.

In both cases the inner maps are `ConcurrentHashMap` and scans copy the key set, so an off-loop
iteration is safe against a concurrent `apply`; it may miss an entry added immediately afterward,
but recovery is idempotent and re-scans next cycle.

### 7.4 Wire format
Opcodes: mutating `WRITE`/`REMOVE` (submitted via `setAsync`, appended to the log) and read-only
`READ`/`SCAN_UIDS`/`SCAN_TYPES`/`CURRENT_STATE` (submitted via `getAsync`, **not** appended).
`apply` payload: `[opcode:1][partition][typeName][uidString]`, plus `[len:4][stateBytes]` for
`WRITE` (strings length-prefixed). Mutating `apply()` is deterministic (pure map mutation; `Uid`s
are generated client-side, so no clocks/RNG) and returns a small serialized ack; read-only
`apply()` (`serialize_response=true`) returns the serialized query result and must never mutate
the map.

### 7.5 Write path (`write_committed` / `remove_committed`)
1. Build the `WRITE`/`REMOVE` command bytes.
2. `future = raftHandle.setAsync(cmd)`; the calling thread blocks on `future.get(writeTimeout)`.
3. The leader appends the entry to its log (a follower's request is forwarded by `REDIRECT`).
4. Followers persist the entry to their logs; when a **majority** have persisted it, it is
   *committed*.
5. Raft invokes `apply(cmd)` on **every** node, in log order, on the event-loop thread; our
   `apply()` performs the single `put`/`remove` on the local map.
6. The submitter's future completes with `apply()`'s result and `write_committed` returns `true`.
   If the submitter is the leader, this is after its own `apply`; if it is a follower (request
   forwarded by `REDIRECT`), the future completes on the **leader's** commit+apply, so the
   follower's *own* map may not have applied the entry yet at that instant (see the read-your-writes
   note in §7.6).

Guarantee when `write_committed` returns `true`: the entry is in a **majority of durable logs**
and has been applied **on the leader** (every node applies it in log order; a follower-submitter's
own map may trail briefly — §7.6). This strengthens Narayana's durability contract from "in this
node's log" to "in a majority of nodes' logs, in a consistent order" (on disk when `useFsync` is
enabled — §9).

### 7.6 Read path (`read_committed`, `allObjUids`, `allTypes`, `currentState`)
Two modes, selected by `readMode`:
- **`linearizable` (default)** — reads are leadership-fenced via `raftHandle.getAsync(...)`, which
  confirms leadership with a majority round-trip (ReadIndex) before serving. **Per-record reads**
  (`read_committed`, `currentState`) run the query through `apply()` on the event-loop thread and
  complete the future. **Scans** (`allObjUids`, `allTypes`) instead issue a lightweight `getAsync`
  barrier to confirm leadership + read-index, then iterate the local applied map off-loop, so a
  large enumeration never stalls consensus (writes are fenced and recovery is idempotent, so the
  brief off-loop window is safe). Correct for **all** roles — including the state store, whose
  reads (`StateManager.activate`) happen on any node: `REDIRECT` transparently forwards a
  follower's `getAsync` to the leader (verified), where the ReadIndex confirmation runs, so the
  read stays linearizable regardless of which node issued it. It also **fences a zombie leader**:
  a demoted-but-unaware leader's read blocks and then fails rather than returning stale data. Cost
  is one majority round-trip per read/scan, acceptable because recovery is periodic and off the
  hot transaction path.
- **`localApplied` (opt-in)** — reads the calling node's local applied map directly, no Raft
  round-trip. It is **only** consistent on a caught-up leader, is **not** fenced against a zombie
  leader, and gives no read-your-writes guarantee on a follower (a follower's `setAsync` future
  completes on the *leader's* apply, not its own). Use only for the action/communication roles
  (whose reads are recovery-path, i.e. leader-only) when the operator accepts the bounded-window
  semantics of §8.1/§10 for lower latency. **Do not** use it for the state store.

(The name reflects the semantics: `localApplied` reads *this node's* applied state, not
necessarily the leader's.)

### 7.7 sync
No-op: durability is already guaranteed at write time by majority commit (+ per-node fsync when
enabled).

### 7.8 Durability, restart & bootstrapping
Each node's `FileBasedLog` (fsync configurable) persists its log; snapshots via
`writeContentTo`/`readContentFrom`, auto-triggered at `max_log_size`. A restarting node installs
the latest snapshot then replays its log through `apply` (during channel connect) before it serves
reads/writes; a lagging/new node is caught up by the leader's snapshot + log shipping.

**`start()` semantics (bootstrapping):** `start()` — called synchronously by `StoreManager` during
Narayana boot — connects the `JChannel` and returns promptly; it does **not** block boot until a
cluster-wide leader exists (optionally it may wait for an initial leader up to a small bounded
timeout, then return regardless). Consequence: with embedded peers the store — and hence the TM's
ability to durably log transactions — is **unavailable until a majority of the configured
`members` is reachable and a leader is elected** (2 of 3, 3 of 5). Until then, `write_committed`
follows the uncertain-outcome handling of §10 item 9, and reads via `getAsync` block/fail without a
leader.

### 7.9 In-memory footprint (consequence/limitation)
The state machine keeps the **entire store in RAM on every node** (a replicated `Map`), unlike the
file/JDBC stores that hold records on disk and read on demand. For the **action** and
**communication** roles this is bounded and transient — only in-doubt transactions and per-node
contact items — so it is a non-issue. For the **state store** it means every persistent
`StateManager` object resides in heap on **every** node simultaneously, so the store's total size
is bounded by the smallest node's heap: suitable for a modest persistent-object set, not for a
large state store. (Snapshots prune the Raft log — §7.8 — so it is the in-memory map, not the log,
that sets the footprint.)

## 8. Leadership-gated recovery

`RaftRecoveryLeadershipListener implements RAFT.RoleChange`, registered by `RaftStoreRuntime`
when the channel connects. **`roleChanged(...)` is invoked synchronously on the RAFT event-loop
thread**, so it must not block it (blocking there stalls consensus — heartbeats, replication,
`apply`). It therefore does only cheap work — record the new role and hand the suspend/resume +
catch-up wait to a dedicated single-thread executor — and returns immediately. On that executor:
- **Becomes leader** → wait until the state machine is caught up, then
  `RecoveryManager.manager().resume()`. "Caught up" means `lastApplied` has reached the leader's
  commit point *after* jgroups-raft has committed its initial no-op for the new term (standard
  Raft: a fresh leader must commit an entry in its own term before its `commitIndex` reflects all
  prior-term committed entries) — so this is not evaluated at the raw instant of the role change.
- **Loses leadership** (higher term *or* lost-quorum view change) → the **async, non-blocking**
  `RecoveryManager.manager().suspend(true)` (it does not wait for an in-progress scan — that tail
  is covered by §8.1). Verified: `RAFT.RoleChange` fires on **both** demotion paths, so a
  partitioned leader *is* eventually suspended — after the JGroups failure-detection/GMS layer
  installs the new view.
- **Startup / before first election** → suspended by default; no node recovers until it is a
  confirmed, caught-up leader. `RecoveryManager` is obtained lazily/guarded to avoid startup
  ordering races.

This uses the existing `PeriodicRecovery` suspend/resume mechanism
(`RecoveryManager.suspend(boolean)` / `resume()`), so non-leaders' recovery thread parks in
`doSuspendedWait()` and never runs `doWorkInternal()`. Narayana has no pre-existing distributed
election; the leadership signal is supplied entirely by Raft.

### 8.1 Guarantee and the demoted-unaware-leader window
This gives **a single recoverer in the common case**, not instantaneous cluster-wide mutual
exclusion. Because a node fences recovery on its *local* leadership view, a demoted-but-unaware
leader (partitioned into a minority, or resuming from a long GC/VM pause) can keep scanning until
it learns it is no longer leader. That window is **bounded** by JGroups failure detection (there
is no Raft check-quorum timer or leader lease — §3.4), and it is kept **safe** by four
independent mechanisms:
1. **Write-fencing** — the zombie leader's `setAsync` writes (`remove_committed`, rewrite
   `write_committed`) cannot reach majority, so they fail; it cannot mutate the replicated store.
2. **Read-fencing** — with `readMode=linearizable` (the default) its recovery reads go through
   `getAsync`, which blocks then fails on a minority leader; the scan cannot even begin on stale
   data. (This protection is lost under `localApplied` — see §7.6.)
3. **Eventual suspension** — the lost-quorum view change fires `RoleChange` → `suspend()`.
4. **Idempotent recovery** — Narayana already tolerates recovery racing with the owning TM: the
   `TransactionStatusManager` liveness check, `XAResource.recover()` + benign `XAER_NOTA` on an
   already-resolved xid, and idempotent `remove_committed`. The residual effect of the window is
   redundant work and benign errors, not store corruption or lost/duplicated commits.

**Scope limit:** drives an in-process (embedded) recovery manager. Out-of-process recovery
managers are out of scope for v1.

## 9. Configuration

`RaftObjectStoreEnvironmentBean`, `@PropertyPrefix("com.arjuna.ats.arjuna.objectstore.jgroupsraft.")`:

| Property | Meaning |
|---|---|
| `partition` | Namespace for this store role's records (`defaultStore` / `stateStore` / `communicationStore`); must be **distinct** per role sharing a runtime (§7.1). |
| `raftId` | This node's `raft_id`; must be an element of `members`. |
| `members` | Static comma-separated cluster list (sized 3 or 5). |
| `jgroupsConfig` | Classpath/file resource for the JGroups stack; **must** include `raft.ELECTION`/`RAFT`/`REDIRECT` (non-leader reads *and* writes are forwarded to the leader through `REDIRECT`; without it a follower `getAsync`/`setAsync` fails with `notCurrentLeader`). A sensible default ships with the module. |
| `clusterName` | `JChannel.connect(...)` name; also the `RaftStoreRuntime` sharing key. |
| `logDir` | Per-node Raft log + snapshot directory. |
| `writeTimeoutMillis` | Bound on `setAsync(...).get(timeout)`. |
| `useFsync` | Per-node Raft log fsync (default `true`). Durability also comes from majority replication, so this is a latency/robustness tunable, not a correctness requirement. |
| `readMode` | `linearizable` (default, `getAsync`, leadership-fenced) or `localApplied` (opt-in; action/communication roles only — see §7.6). |
| `maxLogSize` | Snapshot threshold, passed through to RAFT. |

Wiring (all three roles):
```
com.arjuna.ats.arjuna.objectstore.objectStoreType                    = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore
com.arjuna.ats.arjuna.objectstore.stateStore.objectStoreType         = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore
com.arjuna.ats.arjuna.objectstore.communicationStore.objectStoreType = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore

# distinct partition per role (bean prefix + StoreManager instance name):
com.arjuna.ats.arjuna.objectstore.jgroupsraft.partition                    = defaultStore
com.arjuna.ats.arjuna.objectstore.jgroupsraft.stateStore.partition         = stateStore
com.arjuna.ats.arjuna.objectstore.jgroupsraft.communicationStore.partition = communicationStore
```
State-store role additionally requires:
```
com.arjuna.ats.arjuna.coordinator.transactionLog.writeOptimisation = true
```
`raftId`/`members`/`logDir` may alternatively be supplied via `${…}` substitution directly in
the JGroups XML; the bean provides property-file-driven config and forwards to the stack.

**Shared-runtime constraint:** for the three role beans to share one Raft group (and thus one
leadership — §4), they must specify the **same** `clusterName` and `jgroupsConfig`. Differing
values silently create multiple Raft groups with independent leaders, reintroducing the recovery
ambiguity the shared runtime avoids; `RaftStoreRuntime` validates this at startup and fails fast
on a mismatch. Conversely, the three beans must declare **distinct** `partition` values;
`RaftStoreRuntime` also fails fast if two stores sharing a runtime collide on partition (which
would merge their key-spaces — §7.1).

## 10. Error handling / edge cases

1. **No leader / election in progress** — `REDIRECT` forwards writes to the leader; with no leader,
   `setAsync` does not complete within `writeTimeoutMillis`. This is an *uncertain* outcome, not a
   clean failure — see item 9.
2. **Quorum lost (minority partition)** — writes time out; while the node stays in the minority it
   cannot reach a majority, so the write genuinely does not commit (a clean failure, unlike the
   transition case of item 9). The node cannot become leader, so it will not recover. Correct CP
   behavior (consistency over availability). Split-brain writes are impossible (Raft majority rule).
3. **Leadership change mid-write** — the future may complete exceptionally, or `writeTimeoutMillis`
   may elapse. `WRITE` (blind put keyed by `(partition,type,uid)`) and `REMOVE` are idempotent
   under at-least-once redelivery, so a retry is safe; but the outcome is *uncertain* (the entry
   may still commit) — see item 9.
4. **Post-election read correctness** — before resuming recovery a new leader waits until its
   state machine is caught up to its term's commit point (§8, accounting for the initial-no-op /
   term subtlety), so scans never miss committed logs.
5. **Snapshot consistency vs. liveness** — `writeContentTo`/`readContentFrom` run on the Raft
   event loop with no concurrent `apply`, so they see a consistent map — but they also **block the
   event loop (and thus consensus) for the snapshot's duration**. Fine for the small
   action/communication stores; for a large state store this pauses consensus while serializing.
   `AsyncSnapshot` (§12) is the mitigation.
6. **State-store `writeOptimisation` caveat** — documented requirement (§3.3, §9); otherwise
   `write_uncommitted` is invoked and rejected. Pure JTA is unaffected.
7. **Large states** — Narayana transaction logs are typically small; entry size and
   `snapshot_chunk_size` are noted but not a v1 concern.
8. **Demoted-unaware ("zombie") leader** — a partitioned or GC-paused former leader may keep
   recovering until it learns it is demoted. Bounded by JGroups failure detection and kept safe by
   write-fencing + linearizable read-fencing + eventual `RoleChange` suspension + idempotent
   recovery; full treatment in §8.1. Using `readMode=localApplied` removes the read-fencing leg
   and is therefore restricted to the action/communication roles.
9. **Uncertain write outcome (timeout / leadership loss)** — `writeTimeoutMillis` on
   `future.get(...)` is a client-side *wait* bound; firing it does **not** cancel the pending
   `setAsync`, so a "failed" write may still commit moments later (likewise a leadership-loss
   error, since the entry may already have reached a majority). This is the inherent ambiguity of
   any networked/consensus store (JDBC has it too). Treating it as a clean failure is unsafe: at
   prepare, aborting while the log later commits can leave a *phantom* log — an in-doubt
   transaction that recovery would drive to commit against already-rolled-back resources (a
   heuristic outcome). **Mitigation:** on an uncertain outcome `write_committed` performs a
   linearizable `getAsync` **read-back** of the key and reports success/failure from what actually
   landed. Residual: the still-pending `setAsync` may commit *after* the read-back, materializing
   the log as an in-doubt transaction; that narrow window is handled by recovery's existing
   status/liveness checks + XA idempotency (§8.1) and may surface as a heuristic only under
   pathological crash timing.
10. **Bootstrapping before quorum** — the first node(s) to start have no leader until a majority
    joins; `start()` returns without blocking boot (§7.8), so the store is simply unavailable
    (writes uncertain per item 9, reads fail) until a majority (2 of 3, 3 of 5) is up. Operators
    should expect the TM to be unable to log transactions until then.

## 11. Testing strategy

- **Store-contract unit tests** mirroring `com.hp.mwtests.ts.arjuna.objectstore.ObjectStoreTest`:
  `write/read/remove_committed`, `allObjUids` (both arities), `allTypes`, `currentState`, plus
  assertions that unsupported ops throw — against a single node using `InMemoryLog` + a
  `SHARED_LOOPBACK` stack (like `test-raft.xml`) for speed.
- **Codec** round-trip tests; **StateMachine** tests including a `writeContentTo → readContentFrom`
  snapshot round-trip restoring identical state.
- **Multi-node cluster test** (modeled on `InfinispanClusterTest`/`InfinispanTestBase`): 3 nodes;
  write on one, read on another after commit; kill the leader; verify the new leader has the data.
- **Leader-only-recovery test**: assert non-leaders' `RecoveryManager` is suspended and the
  leader's resumes on role change; instrument a recovery module to prove no double recovery **under
  stable leadership**.
- **Overlapping-recovery safety test**: force a leadership transition mid-scan and assert the
  outcome is still correct (idempotent — no lost or duplicated commits), matching the §8.1
  guarantee.
- **Partition isolation** test (same type name in different role partitions does not collide).
- **Idempotent-retry** test (duplicate `WRITE` after a simulated leadership change → consistent
  state).
- **Uncertain-write test**: force a `setAsync` timeout while the entry still commits; assert the
  `getAsync` read-back reconciliation reports the correct outcome and that an abort leaves no
  phantom in-doubt log (§10 item 9).
- **Bootstrapping test**: start a single node of a 3-member cluster; assert boot completes without
  blocking, writes are unavailable until a second node joins (quorum), then succeed (§7.8, §10
  item 10).
- **End-to-end JTA recovery** (higher effort, likely qa-suite level): run an `AtomicAction`, crash
  the owning node, verify a surviving leader completes recovery from the replicated log.

## 12. Future work
- Dynamic membership via `addServer`/`removeServer` (the config/code is structured for it).
- `AsyncSnapshot` for large stores.
- Out-of-process recovery-manager leadership signalling.
- Optional full shadow-protocol fidelity if the state-store role must be used without
  `writeOptimisation=true`.

## 13. Build integration
- Add the `jgroups-raft-objectstore` module under `ArjunaCore` and register it in the reactor.
- `jgroups-raft` is a compile dependency of the **new module only**, never of core `arjuna`.
- Note: jgroups-raft is currently `2.0.0.Final-SNAPSHOT`; pin/track a released version before
  shipping.
