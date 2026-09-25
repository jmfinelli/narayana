# jgroups-raft Object Store (JBTM-4038) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a drop-in Narayana `ObjectStoreAPI` backend that persists transaction/recovery state into a replicated jgroups-raft log, giving high availability of transaction logs with leader-only, idempotent recovery.

**Architecture:** A new standalone Maven module (`ArjunaCore/jgroups-raft-objectstore`) provides `RaftObjectStore` (a committed-only `ObjectStoreAPI` adaptor). Each JVM embeds one Raft node — a shared `RaftStoreRuntime` owns a single `JChannel` + `RaftHandle` + `ObjectStoreStateMachine`, shared by the ≤3 store roles (action/state/communication), each namespaced by a `partition`. Writes go through Raft consensus (`setAsync`); reads are leadership-fenced (`getAsync`) by default. Raft leadership drives Narayana's `RecoveryManager` suspend/resume so only the leader recovers.

**Tech Stack:** Java 17, Narayana 7.3.5.Final-SNAPSHOT (arjuna module APIs), jgroups-raft 2.0.0.Final-SNAPSHOT on jgroups 5.5.7.Final, JUnit 5 (Jupiter), Maven.

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec and verified against the codebase.

- **Java version:** 17 (`maven.compiler.release=17`). jgroups-raft requires Java 17.
- **Narayana project version:** `7.3.5.Final-SNAPSHOT`. New module parent = `org.jboss.narayana.arjunacore:arjunacore-all:7.3.5.Final-SNAPSHOT`, `<relativePath>../pom.xml</relativePath>`.
- **Module location / artifact:** `ArjunaCore/jgroups-raft-objectstore`, artifactId `jgroups-raft-objectstore`, packaging `jar`.
- **Source layout (match sibling modules `arjuna`/`txoj`):** main sources under `classes/`, test sources under `tests/classes/`, non-Java resources under `etc/`, test resources under `src/test/resources/`. There is **no** `src/main/java`.
- **Production package:** `com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft`. **Test package:** `com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft`.
- **jgroups-raft dependency (`org.jgroups:jgroups-raft:2.0.0.Final-SNAPSHOT`) is a compile dependency of THIS module ONLY, never of core `arjuna`.** Track/pin a released jgroups-raft version before shipping (§13 of spec).
- **Config bean prefix:** `@PropertyPrefix(prefix = "com.arjuna.ats.arjuna.objectstore.jgroupsraft.")`.
- **Committed-only store:** `fullCommitNeeded()` returns `false`. `write_committed`/`read_committed`/`remove_committed`/`allObjUids` (both arities)/`allTypes`/`currentState`/`sync` (no-op)/`getStoreName`/`start`/`stop` are supported. `write_uncommitted`, `read_uncommitted`, `remove_uncommitted`, `commit_state`, `hide_state`, `reveal_state`, `isType` throw `new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented())` — mirroring `SlotStoreAdaptor`.
- **License header, Java (main + test) sources** — first lines of every `.java` file:
  ```java
  /*
     Copyright The Narayana Authors
     SPDX-License-Identifier: Apache-2.0
   */
  ```
- **License header, `pom.xml`** — first lines:
  ```xml
  <!--
     Copyright The Narayana Authors
     SPDX short identifier: Apache-2.0
   -->
  ```
- **Test framework:** JUnit 5 (Jupiter) — `org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Assertions.*`.

---

## Reference: exact external signatures used by this plan

These were verified against the two checked-out repos. Implementers should not re-derive them.

**Narayana (`org.jboss.narayana.arjunacore:arjuna`):**
- `com.arjuna.ats.arjuna.objectstore.ObjectStoreAPI extends ParticipantStore, RecoveryStore` (→ `TxLog` → `BaseStore`). Union of methods a store must implement:
  - `String getStoreName()` · `void start()` · `void stop()`
  - `boolean remove_committed(Uid u, String tn) throws ObjectStoreException`
  - `boolean write_committed(Uid u, String tn, OutputObjectState buff) throws ObjectStoreException`
  - `void sync() throws java.io.SyncFailedException, ObjectStoreException`
  - `boolean commit_state(Uid u, String tn) throws ObjectStoreException`
  - `InputObjectState read_committed(Uid u, String tn) throws ObjectStoreException`
  - `InputObjectState read_uncommitted(Uid u, String tn) throws ObjectStoreException`
  - `boolean remove_uncommitted(Uid u, String tn) throws ObjectStoreException`
  - `boolean write_uncommitted(Uid u, String tn, OutputObjectState buff) throws ObjectStoreException`
  - `boolean fullCommitNeeded()`
  - `boolean allObjUids(String s, InputObjectState buff, int m) throws ObjectStoreException`
  - `boolean allObjUids(String s, InputObjectState buff) throws ObjectStoreException`
  - `boolean allTypes(InputObjectState buff) throws ObjectStoreException`
  - `int currentState(Uid u, String tn) throws ObjectStoreException`
  - `boolean hide_state(Uid u, String tn) throws ObjectStoreException`
  - `boolean reveal_state(Uid u, String tn) throws ObjectStoreException`
  - `boolean isType(Uid u, String tn, int st) throws ObjectStoreException`
- `com.arjuna.ats.arjuna.common.Uid`: `Uid()` (new unique), `Uid(String uidString)`, `String stringForm()`, static `Uid.nullUid()`.
- `com.arjuna.ats.arjuna.state.OutputObjectState`: `byte[] buffer()` returns exactly the packed bytes; `packString(String)`.
- `com.arjuna.ats.arjuna.state.InputObjectState`: `InputObjectState()`, `InputObjectState(Uid newUid, String tName, byte[] buff)`, `void setBuffer(byte[])`, `String unpackString()`.
- `com.arjuna.ats.internal.arjuna.common.UidHelper`: static `void packInto(Uid u, OutputBuffer buff)`, static `Uid unpackFrom(InputBuffer buff) throws IOException` (`OutputObjectState`/`InputObjectState` are valid arguments).
- `com.arjuna.ats.arjuna.objectstore.StateStatus`: `OS_COMMITTED = 1`, `OS_UNKNOWN = -1`.
- `com.arjuna.ats.arjuna.exceptions.ObjectStoreException`: ctors `(String)` and `(Throwable)`.
- `com.arjuna.ats.arjuna.logging.tsLogger`: `tsLogger.i18NLogger.get_method_not_implemented()` returns a `String`.
- `com.arjuna.ats.arjuna.recovery.RecoveryManager`: static `RecoveryManager manager()`, `void suspend(boolean async)`, `void resume()`.
- Bean plumbing: `com.arjuna.common.internal.util.propertyservice.PropertyPrefix` (annotation, `prefix()`), `com.arjuna.common.internal.util.propertyservice.BeanPopulator` (`getDefaultInstance(Class)`, `getNamedInstance(Class, String)`). `StoreManager` selects the constructor whose single parameter type name ends with `EnvironmentBean` via `ClassloadingUtility.loadAndInstantiateClass`.

**jgroups-raft (`org.jgroups:jgroups-raft`):**
- `org.jgroups.raft.StateMachine`: `byte[] apply(byte[] data, int offset, int length, boolean serialize_response)` (no checked exceptions), `void readContentFrom(DataInput in)` (no checked exceptions), `void writeContentTo(DataOutput out) throws Exception`.
- `org.jgroups.raft.RaftHandle`: `RaftHandle(JChannel ch, StateMachine sm)`; `CompletableFuture<byte[]> setAsync(byte[] buf, int offset, int length, Options options) throws Exception`; `CompletableFuture<byte[]> getAsync(byte[] buf, int offset, int length, Options options) throws Exception`; default overloads `setAsync(buf,off,len)`/`getAsync(buf,off,len)` (pass `null` options); `boolean isLeader()`; `Address leader()`; `RaftHandle addRoleListener(RAFT.RoleChange)`; `RAFT raft()`; `long lastApplied()`; `long commitIndex()`; `void snapshot() throws Exception`.
- `org.jgroups.raft.Options`: `new Options()`, `Options.create(boolean ignore_retval)`, `Options.DEFAULT_OPTIONS`; `null` is accepted by set/getAsync.
- `org.jgroups.protocols.raft.RAFT`: fluent `RAFT raftId(String)`, `RAFT members(java.util.Collection<String>)`, `RAFT logDir(String)`, `RAFT logClass(String)`, `RAFT maxLogSize(long)`; `RAFT addRoleListener(RoleChange)`. Obtain via `ch.getProtocolStack().findProtocol(RAFT.class)`.
- `org.jgroups.protocols.raft.RAFT.RoleChange` (interface): `void roleChanged(Role role)`.
- `org.jgroups.protocols.raft.Role` (enum): `Follower`, `Leader`, `Learner` — **there is NO `Candidate`**.
- Log classes (FQCN strings for `log_class`): `org.jgroups.protocols.raft.FileBasedLog` (default, on-disk), `org.jgroups.protocols.raft.InMemoryLog` (tests).
- `org.jgroups.JChannel(String props)` accepts a classpath resource/file name (e.g. `new JChannel("raft-objectstore-test.xml")`), then `channel.connect(clusterName)`.

---

## File structure

New module `ArjunaCore/jgroups-raft-objectstore/`:

| File | Responsibility |
|---|---|
| `pom.xml` | Module POM: parent, `jgroups-raft` + `arjuna` deps, JUnit 5, `classes`/`tests/classes` source dirs. |
| `classes/…/jgroupsraft/StoreCommand.java` | Wire codec: encode/decode commands (`WRITE`/`REMOVE`/`READ`/`SCAN_UIDS`/`SCAN_TYPES`/`CURRENT_STATE`) and query results. Pure, no Raft. |
| `classes/…/jgroupsraft/ObjectStoreStateMachine.java` | Replicated `Map<partition,Map<type,Map<uid,byte[]>>>`; `apply()` (mutating + read-only), snapshot `writeContentTo`/`readContentFrom`, off-loop local accessors. |
| `classes/…/jgroupsraft/RaftObjectStoreEnvironmentBean.java` | `@PropertyPrefix` config bean (partition, raftId, members, jgroupsConfig, clusterName, logDir, writeTimeoutMillis, useFsync, readMode, maxLogSize). |
| `classes/…/jgroupsraft/RecoveryControl.java` | Seam interface (`suspend()`/`resume()`) + default `RecoveryManagerControl` impl. Enables unit-testing the listener without a live RecoveryManager. |
| `classes/…/jgroupsraft/RaftRecoveryLeadershipListener.java` | `implements RAFT.RoleChange`; off-loads suspend/resume + caught-up wait to a single-thread executor. |
| `classes/…/jgroupsraft/RaftStoreRuntime.java` | Per-JVM runtime keyed by `raftId`; owns `JChannel`+`RaftHandle`+`ObjectStoreStateMachine`; ref-counted; `write`/`remove`/`read`/`scanUids`/`scanTypes`/`currentState`; fail-fast validation; uncertain-write read-back. |
| `classes/…/jgroupsraft/RaftObjectStore.java` | `implements ObjectStoreAPI`; committed-only adaptor delegating to the runtime; carries `partition`. |
| `etc/raft-objectstore-jgroups.xml` | Default production JGroups+RAFT stack shipped with the module. |
| `src/test/resources/raft-objectstore-test.xml` | Test stack: `SHARED_LOOPBACK` + `InMemoryLog`. |
| `tests/classes/…/jgroupsraft/*.java` | JUnit 5 tests (per task below). |

---

### Task 1: Module scaffolding, dependency, and single-node bootstrap smoke test

Creates the module, wires it into the reactor, installs the local jgroups-raft SNAPSHOT, ships the test JGroups stack, and proves jgroups-raft is resolvable and a one-member cluster elects a leader.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/pom.xml`
- Modify: `ArjunaCore/pom.xml` (add `<module>`)
- Create: `ArjunaCore/jgroups-raft-objectstore/src/test/resources/raft-objectstore-test.xml`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/BootstrapSmokeTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: the buildable module and the test resource `raft-objectstore-test.xml` (classpath resource name `raft-objectstore-test.xml`) used by later tasks.

- [ ] **Step 1: Install the local jgroups-raft SNAPSHOT into the local Maven repo**

The `2.0.0.Final-SNAPSHOT` is a local checkout and is not published to the JBoss repositories, so it must be installed first.

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/jgroups-raft
./mvnw -q -DskipTests install || mvn -q -DskipTests install
```
Expected: `BUILD SUCCESS`; artifact `org.jgroups:jgroups-raft:2.0.0.Final-SNAPSHOT` now present under `~/.m2/repository/org/jgroups/jgroups-raft/2.0.0.Final-SNAPSHOT/`.

- [ ] **Step 2: Create the module POM**

Create `ArjunaCore/jgroups-raft-objectstore/pom.xml`:
```xml
<!--
   Copyright The Narayana Authors
   SPDX short identifier: Apache-2.0
 -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.jboss.narayana.arjunacore</groupId>
    <artifactId>arjunacore-all</artifactId>
    <version>7.3.5.Final-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>jgroups-raft-objectstore</artifactId>
  <packaging>jar</packaging>
  <name>Narayana: ArjunaCore jgroups-raft-objectstore</name>
  <description>Replicated Raft-backed ObjectStore (JBTM-4038)</description>

  <properties>
    <version.org.jgroups.raft>2.0.0.Final-SNAPSHOT</version.org.jgroups.raft>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.jboss.narayana</groupId>
        <artifactId>test-dependency-management</artifactId>
        <version>${project.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.jboss.narayana</groupId>
      <artifactId>common</artifactId>
    </dependency>
    <dependency>
      <groupId>org.jboss.narayana.arjunacore</groupId>
      <artifactId>arjuna</artifactId>
    </dependency>
    <dependency>
      <groupId>org.jgroups</groupId>
      <artifactId>jgroups-raft</artifactId>
      <version>${version.org.jgroups.raft}</version>
    </dependency>
    <dependency>
      <groupId>org.jboss.logging</groupId>
      <artifactId>jboss-logging</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <sourceDirectory>classes</sourceDirectory>
    <testSourceDirectory>tests/classes</testSourceDirectory>
    <testResources>
      <testResource>
        <directory>src/test/resources</directory>
      </testResource>
    </testResources>
    <resources>
      <resource>
        <directory>etc</directory>
      </resource>
    </resources>
  </build>
</project>
```

- [ ] **Step 3: Register the module in the ArjunaCore reactor**

In `ArjunaCore/pom.xml`, add the new module to the `<modules>` block. Change:
```xml
  <modules>
    <module>arjuna</module>
    <module>txoj</module>
    <module>arjunacore</module>
    <module>arjuna/services</module>
  </modules>
```
to:
```xml
  <modules>
    <module>arjuna</module>
    <module>txoj</module>
    <module>arjunacore</module>
    <module>arjuna/services</module>
    <module>jgroups-raft-objectstore</module>
  </modules>
```

- [ ] **Step 4: Create the test JGroups stack**

Create `ArjunaCore/jgroups-raft-objectstore/src/test/resources/raft-objectstore-test.xml` (SHARED_LOOPBACK so multiple nodes run in one JVM; `InMemoryLog` so no files are written and no per-node log dir is needed). `members`/`raft_id`/`log_dir` are overridden programmatically by `RaftStoreRuntime`, so the placeholders here just provide defaults:
```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    <SHARED_LOOPBACK />
    <SHARED_LOOPBACK_PING />
    <pbcast.NAKACK2 xmit_interval="500"
                    use_mcast_xmit="false"
                    discard_delivered_msgs="true"/>
    <UNICAST3 xmit_interval="500" conn_expiry_timeout="0"/>
    <pbcast.STABLE desired_avg_gossip="50000" max_bytes="4M"/>
    <pbcast.GMS print_local_addr="false" print_view_details="false" join_timeout="1000"/>
    <FRAG4 frag_size="60K"/>
    <raft.ELECTION/>
    <raft.RAFT members="${raft_members:A}"
               raft_id="${raft_id:A}"
               log_class="org.jgroups.protocols.raft.InMemoryLog"/>
    <raft.REDIRECT/>
</config>
```

- [ ] **Step 5: Write the failing bootstrap smoke test**

Create `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/BootstrapSmokeTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jgroups.JChannel;
import org.jgroups.protocols.raft.RAFT;
import org.jgroups.raft.RaftHandle;
import org.jgroups.raft.StateMachine;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataOutput;

public class BootstrapSmokeTest {

    static final class NoopStateMachine implements StateMachine {
        public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) {
            return serialize_response ? new byte[0] : null;
        }
        public void readContentFrom(DataInput in) { }
        public void writeContentTo(DataOutput out) { }
    }

    @Test
    public void singleNodeBecomesLeader() throws Exception {
        JChannel ch = new JChannel("raft-objectstore-test.xml");
        RAFT raft = ch.getProtocolStack().findProtocol(RAFT.class);
        raft.raftId("A").members(List.of("A"));
        RaftHandle handle = new RaftHandle(ch, new NoopStateMachine());
        try {
            ch.connect("bootstrap-smoke");
            long deadline = System.currentTimeMillis() + 10_000;
            while (!handle.isLeader() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(handle.isLeader(), "single-member cluster should elect itself leader");
        } finally {
            ch.close();
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore -am test -Dtest=BootstrapSmokeTest
```
Expected: build reaches the new module and the test **compiles and runs** (jgroups-raft resolved). It should PASS once the resource and deps are correct; if the module or dependency wiring is wrong it FAILS at compile/resolve — fix until it runs green.

- [ ] **Step 7: Commit**

```bash
git add ArjunaCore/pom.xml ArjunaCore/jgroups-raft-objectstore/pom.xml \
        ArjunaCore/jgroups-raft-objectstore/src/test/resources/raft-objectstore-test.xml \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/BootstrapSmokeTest.java
git commit -m "feat(JBTM-4038): scaffold jgroups-raft-objectstore module + bootstrap smoke test"
```

---

### Task 2: `StoreCommand` codec

Pure serialization of commands and query results. No Raft, no Narayana types — trivially unit-testable.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/StoreCommand.java`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/StoreCommandTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (exact API relied on by Tasks 3 & 5):
  - Opcode constants: `byte WRITE=1, REMOVE=2, READ=3, SCAN_UIDS=4, SCAN_TYPES=5, CURRENT_STATE=6`.
  - Fields: `byte opcode; String partition; String typeName; String uidString; byte[] state;`
  - `byte[] encode()` · static `StoreCommand decode(byte[] data, int offset, int length)`
  - Factories: `static StoreCommand write(String partition, String typeName, String uid, byte[] state)`, `remove(String,String,String)`, `read(String,String,String)`, `scanUids(String partition, String typeName)`, `scanTypes(String partition)`, `currentState(String,String,String)`
  - Result codecs: `static byte[] encodeBoolean(boolean)`, `static boolean decodeBoolean(byte[])`, `static byte[] encodeInt(int)`, `static int decodeInt(byte[])`, `static byte[] encodeBytesResult(byte[] valueOrNull)`, `static byte[] decodeBytesResult(byte[])` (returns null when absent), `static byte[] encodeStringList(java.util.List<String>)`, `static java.util.List<String> decodeStringList(byte[])`

- [ ] **Step 1: Write the failing test**

Create `StoreCommandTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.StoreCommand;

public class StoreCommandTest {

    @Test
    public void writeCommandRoundTrips() {
        byte[] state = {9, 8, 7, 6};
        StoreCommand c = StoreCommand.write("defaultStore", "/StateManager/AtomicAction", "uid-1", state);
        byte[] wire = c.encode();
        StoreCommand d = StoreCommand.decode(wire, 0, wire.length);
        assertEquals(StoreCommand.WRITE, d.opcode);
        assertEquals("defaultStore", d.partition);
        assertEquals("/StateManager/AtomicAction", d.typeName);
        assertEquals("uid-1", d.uidString);
        assertArrayEquals(state, d.state);
    }

    @Test
    public void decodeHonoursOffsetAndLength() {
        StoreCommand c = StoreCommand.remove("p", "t", "u");
        byte[] inner = c.encode();
        byte[] framed = new byte[inner.length + 5];
        System.arraycopy(inner, 0, framed, 3, inner.length);
        StoreCommand d = StoreCommand.decode(framed, 3, inner.length);
        assertEquals(StoreCommand.REMOVE, d.opcode);
        assertEquals("p", d.partition);
    }

    @Test
    public void scanTypesHasNoTypeOrUid() {
        StoreCommand c = StoreCommand.scanTypes("stateStore");
        byte[] wire = c.encode();
        StoreCommand d = StoreCommand.decode(wire, 0, wire.length);
        assertEquals(StoreCommand.SCAN_TYPES, d.opcode);
        assertEquals("stateStore", d.partition);
    }

    @Test
    public void resultCodecsRoundTrip() {
        assertTrue(StoreCommand.decodeBoolean(StoreCommand.encodeBoolean(true)));
        assertFalse(StoreCommand.decodeBoolean(StoreCommand.encodeBoolean(false)));
        assertEquals(42, StoreCommand.decodeInt(StoreCommand.encodeInt(42)));

        byte[] v = {1, 2, 3};
        assertArrayEquals(v, StoreCommand.decodeBytesResult(StoreCommand.encodeBytesResult(v)));
        assertNull(StoreCommand.decodeBytesResult(StoreCommand.encodeBytesResult(null)));

        List<String> list = List.of("a", "b/c", "d");
        assertEquals(list, StoreCommand.decodeStringList(StoreCommand.encodeStringList(list)));
        assertTrue(StoreCommand.decodeStringList(StoreCommand.encodeStringList(List.of())).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=StoreCommandTest
```
Expected: FAIL — compilation error, `StoreCommand` does not exist.

- [ ] **Step 3: Write the implementation**

Create `StoreCommand.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire codec for all Raft object-store commands and query results.
 * Mutating opcodes (WRITE, REMOVE) are submitted via setAsync (logged);
 * read-only opcodes (READ, SCAN_UIDS, SCAN_TYPES, CURRENT_STATE) via getAsync.
 */
public final class StoreCommand {

    public static final byte WRITE = 1;
    public static final byte REMOVE = 2;
    public static final byte READ = 3;
    public static final byte SCAN_UIDS = 4;
    public static final byte SCAN_TYPES = 5;
    public static final byte CURRENT_STATE = 6;

    public final byte opcode;
    public final String partition;
    public final String typeName;
    public final String uidString;
    public final byte[] state;

    public StoreCommand(byte opcode, String partition, String typeName, String uidString, byte[] state) {
        this.opcode = opcode;
        this.partition = partition;
        this.typeName = typeName;
        this.uidString = uidString;
        this.state = state;
    }

    public static StoreCommand write(String partition, String typeName, String uid, byte[] state) {
        return new StoreCommand(WRITE, partition, typeName, uid, state);
    }
    public static StoreCommand remove(String partition, String typeName, String uid) {
        return new StoreCommand(REMOVE, partition, typeName, uid, null);
    }
    public static StoreCommand read(String partition, String typeName, String uid) {
        return new StoreCommand(READ, partition, typeName, uid, null);
    }
    public static StoreCommand scanUids(String partition, String typeName) {
        return new StoreCommand(SCAN_UIDS, partition, typeName, "", null);
    }
    public static StoreCommand scanTypes(String partition) {
        return new StoreCommand(SCAN_TYPES, partition, "", "", null);
    }
    public static StoreCommand currentState(String partition, String typeName, String uid) {
        return new StoreCommand(CURRENT_STATE, partition, typeName, uid, null);
    }

    public byte[] encode() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(opcode);
            out.writeUTF(partition == null ? "" : partition);
            out.writeUTF(typeName == null ? "" : typeName);
            out.writeUTF(uidString == null ? "" : uidString);
            if (opcode == WRITE) {
                byte[] s = state == null ? new byte[0] : state;
                out.writeInt(s.length);
                out.write(s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static StoreCommand decode(byte[] data, int offset, int length) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, offset, length))) {
            byte opcode = in.readByte();
            String partition = in.readUTF();
            String typeName = in.readUTF();
            String uidString = in.readUTF();
            byte[] state = null;
            if (opcode == WRITE) {
                int len = in.readInt();
                state = new byte[len];
                in.readFully(state);
            }
            return new StoreCommand(opcode, partition, typeName, uidString, state);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- query-result codecs ----

    public static byte[] encodeBoolean(boolean b) {
        return new byte[] { (byte) (b ? 1 : 0) };
    }
    public static boolean decodeBoolean(byte[] resp) {
        return resp != null && resp.length > 0 && resp[0] != 0;
    }

    public static byte[] encodeInt(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }
    public static int decodeInt(byte[] resp) {
        return ((resp[0] & 0xFF) << 24) | ((resp[1] & 0xFF) << 16) | ((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF);
    }

    public static byte[] encodeBytesResult(byte[] valueOrNull) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeBoolean(valueOrNull != null);
            if (valueOrNull != null) {
                out.writeInt(valueOrNull.length);
                out.write(valueOrNull);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
    public static byte[] decodeBytesResult(byte[] resp) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(resp))) {
            if (!in.readBoolean()) {
                return null;
            }
            byte[] value = new byte[in.readInt()];
            in.readFully(value);
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeStringList(List<String> values) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(values.size());
            for (String s : values) {
                out.writeUTF(s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
    public static List<String> decodeStringList(byte[] resp) {
        List<String> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(resp))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                out.add(in.readUTF());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=StoreCommandTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/StoreCommand.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/StoreCommandTest.java
git commit -m "feat(JBTM-4038): add StoreCommand wire codec"
```

---

### Task 3: `ObjectStoreStateMachine`

The replicated map, the deterministic `apply()` (mutating + read-only), snapshot round-trip, and off-loop local accessors. Tested by calling `apply()` directly — no Raft cluster needed.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/ObjectStoreStateMachine.java`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/ObjectStoreStateMachineTest.java`

**Interfaces:**
- Consumes: `StoreCommand` (Task 2).
- Produces (relied on by Task 5):
  - `implements org.jgroups.raft.StateMachine`
  - `byte[] localRead(String partition, String typeName, String uid)` (null if absent)
  - `java.util.List<String> localScanUids(String partition, String typeName)` (empty/null type ⇒ all uids in partition)
  - `java.util.List<String> localScanTypes(String partition)`
  - `int localCurrentState(String partition, String typeName, String uid)` (`OS_COMMITTED`/`OS_UNKNOWN` numeric values 1 / -1)

- [ ] **Step 1: Write the failing test**

Create `ObjectStoreStateMachineTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.ObjectStoreStateMachine;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.StoreCommand;

public class ObjectStoreStateMachineTest {

    private static byte[] apply(ObjectStoreStateMachine sm, StoreCommand cmd) {
        byte[] wire = cmd.encode();
        return sm.apply(wire, 0, wire.length, true);
    }

    @Test
    public void writeThenReadViaApply() {
        ObjectStoreStateMachine sm = new ObjectStoreStateMachine();
        byte[] state = {1, 2, 3};
        assertTrue(StoreCommand.decodeBoolean(apply(sm, StoreCommand.write("p", "t", "u", state))));
        byte[] read = StoreCommand.decodeBytesResult(apply(sm, StoreCommand.read("p", "t", "u")));
        assertArrayEquals(state, read);
        assertEquals(1, StoreCommand.decodeInt(apply(sm, StoreCommand.currentState("p", "t", "u"))));
    }

    @Test
    public void removeThenReadReturnsNull() {
        ObjectStoreStateMachine sm = new ObjectStoreStateMachine();
        apply(sm, StoreCommand.write("p", "t", "u", new byte[] {9}));
        apply(sm, StoreCommand.remove("p", "t", "u"));
        assertNull(StoreCommand.decodeBytesResult(apply(sm, StoreCommand.read("p", "t", "u"))));
        assertEquals(-1, StoreCommand.decodeInt(apply(sm, StoreCommand.currentState("p", "t", "u"))));
    }

    @Test
    public void scanUidsAndTypesAreScopedToPartitionAndType() {
        ObjectStoreStateMachine sm = new ObjectStoreStateMachine();
        apply(sm, StoreCommand.write("p", "typeA", "u1", new byte[] {1}));
        apply(sm, StoreCommand.write("p", "typeA", "u2", new byte[] {1}));
        apply(sm, StoreCommand.write("p", "typeB", "u3", new byte[] {1}));

        List<String> uidsA = StoreCommand.decodeStringList(apply(sm, StoreCommand.scanUids("p", "typeA")));
        assertEquals(2, uidsA.size());
        assertTrue(uidsA.contains("u1") && uidsA.contains("u2"));

        List<String> types = StoreCommand.decodeStringList(apply(sm, StoreCommand.scanTypes("p")));
        assertTrue(types.contains("typeA") && types.contains("typeB"));
    }

    @Test
    public void partitionsDoNotCollide() {
        ObjectStoreStateMachine sm = new ObjectStoreStateMachine();
        apply(sm, StoreCommand.write("defaultStore", "sameType", "sameUid", new byte[] {1}));
        apply(sm, StoreCommand.write("stateStore", "sameType", "sameUid", new byte[] {2}));
        assertArrayEquals(new byte[] {1}, sm.localRead("defaultStore", "sameType", "sameUid"));
        assertArrayEquals(new byte[] {2}, sm.localRead("stateStore", "sameType", "sameUid"));
    }

    @Test
    public void readOnlyApplyDoesNotMutate() {
        ObjectStoreStateMachine sm = new ObjectStoreStateMachine();
        apply(sm, StoreCommand.read("p", "t", "missing"));
        apply(sm, StoreCommand.scanUids("p", "t"));
        apply(sm, StoreCommand.scanTypes("p"));
        assertTrue(sm.localScanTypes("p").isEmpty(), "read-only apply must not create entries");
    }

    @Test
    public void snapshotRoundTripRestoresIdenticalState() throws Exception {
        ObjectStoreStateMachine original = new ObjectStoreStateMachine();
        apply(original, StoreCommand.write("p1", "t1", "u1", new byte[] {1, 1}));
        apply(original, StoreCommand.write("p1", "t2", "u2", new byte[] {2, 2}));
        apply(original, StoreCommand.write("p2", "t1", "u3", new byte[] {3, 3}));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        original.writeContentTo(new DataOutputStream(bos));

        ObjectStoreStateMachine restored = new ObjectStoreStateMachine();
        restored.readContentFrom(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        assertArrayEquals(new byte[] {1, 1}, restored.localRead("p1", "t1", "u1"));
        assertArrayEquals(new byte[] {2, 2}, restored.localRead("p1", "t2", "u2"));
        assertArrayEquals(new byte[] {3, 3}, restored.localRead("p2", "t1", "u3"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=ObjectStoreStateMachineTest
```
Expected: FAIL — `ObjectStoreStateMachine` does not exist.

- [ ] **Step 3: Write the implementation**

Create `ObjectStoreStateMachine.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jgroups.raft.StateMachine;

import com.arjuna.ats.arjuna.objectstore.StateStatus;

/**
 * Replicated materialized view of the committed Raft log.
 * partition -&gt; typeName -&gt; uidString -&gt; state bytes.
 * apply() is invoked only after consensus, on the single Raft event-loop thread.
 * Mutating opcodes (WRITE/REMOVE) mutate the map; read-only opcodes never do.
 */
public final class ObjectStoreStateMachine implements StateMachine {

    private final Map<String, Map<String, Map<String, byte[]>>> state = new ConcurrentHashMap<>();

    @Override
    public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) {
        StoreCommand cmd = StoreCommand.decode(data, offset, length);
        switch (cmd.opcode) {
            case StoreCommand.WRITE: {
                state.computeIfAbsent(cmd.partition, p -> new ConcurrentHashMap<>())
                     .computeIfAbsent(cmd.typeName, t -> new ConcurrentHashMap<>())
                     .put(cmd.uidString, cmd.state == null ? new byte[0] : cmd.state);
                return serialize_response ? StoreCommand.encodeBoolean(true) : null;
            }
            case StoreCommand.REMOVE: {
                Map<String, Map<String, byte[]>> p = state.get(cmd.partition);
                if (p != null) {
                    Map<String, byte[]> t = p.get(cmd.typeName);
                    if (t != null) {
                        t.remove(cmd.uidString);
                    }
                }
                // remove is idempotent: success regardless of prior presence
                return serialize_response ? StoreCommand.encodeBoolean(true) : null;
            }
            case StoreCommand.READ:
                return serialize_response
                        ? StoreCommand.encodeBytesResult(localRead(cmd.partition, cmd.typeName, cmd.uidString))
                        : null;
            case StoreCommand.SCAN_UIDS:
                return serialize_response
                        ? StoreCommand.encodeStringList(localScanUids(cmd.partition, cmd.typeName))
                        : null;
            case StoreCommand.SCAN_TYPES:
                return serialize_response
                        ? StoreCommand.encodeStringList(localScanTypes(cmd.partition))
                        : null;
            case StoreCommand.CURRENT_STATE:
                return serialize_response
                        ? StoreCommand.encodeInt(localCurrentState(cmd.partition, cmd.typeName, cmd.uidString))
                        : null;
            default:
                return serialize_response ? new byte[0] : null;
        }
    }

    /** Off-loop read of a single record; null if absent. Never mutates. */
    public byte[] localRead(String partition, String typeName, String uid) {
        Map<String, Map<String, byte[]>> p = state.get(partition);
        if (p == null) {
            return null;
        }
        Map<String, byte[]> t = p.get(typeName);
        return t == null ? null : t.get(uid);
    }

    /** Off-loop scan of uids for a type; empty/null type returns every uid in the partition. */
    public List<String> localScanUids(String partition, String typeName) {
        List<String> uids = new ArrayList<>();
        Map<String, Map<String, byte[]>> p = state.get(partition);
        if (p == null) {
            return uids;
        }
        if (typeName == null || typeName.isEmpty()) {
            for (Map<String, byte[]> t : p.values()) {
                uids.addAll(t.keySet());
            }
        } else {
            Map<String, byte[]> t = p.get(typeName);
            if (t != null) {
                uids.addAll(t.keySet());
            }
        }
        return uids;
    }

    /** Off-loop scan of type names present in the partition. */
    public List<String> localScanTypes(String partition) {
        Map<String, Map<String, byte[]>> p = state.get(partition);
        return p == null ? new ArrayList<>() : new ArrayList<>(p.keySet());
    }

    /** OS_COMMITTED (1) if present, else OS_UNKNOWN (-1). */
    public int localCurrentState(String partition, String typeName, String uid) {
        return localRead(partition, typeName, uid) != null ? StateStatus.OS_COMMITTED : StateStatus.OS_UNKNOWN;
    }

    @Override
    public void writeContentTo(DataOutput out) throws Exception {
        out.writeInt(state.size());
        for (Map.Entry<String, Map<String, Map<String, byte[]>>> pe : state.entrySet()) {
            out.writeUTF(pe.getKey());
            out.writeInt(pe.getValue().size());
            for (Map.Entry<String, Map<String, byte[]>> te : pe.getValue().entrySet()) {
                out.writeUTF(te.getKey());
                out.writeInt(te.getValue().size());
                for (Map.Entry<String, byte[]> ue : te.getValue().entrySet()) {
                    out.writeUTF(ue.getKey());
                    out.writeInt(ue.getValue().length);
                    out.write(ue.getValue());
                }
            }
        }
    }

    @Override
    public void readContentFrom(DataInput in) {
        try {
            state.clear();
            int np = in.readInt();
            for (int i = 0; i < np; i++) {
                String partition = in.readUTF();
                int nt = in.readInt();
                Map<String, Map<String, byte[]>> pm = new ConcurrentHashMap<>();
                for (int j = 0; j < nt; j++) {
                    String type = in.readUTF();
                    int nu = in.readInt();
                    Map<String, byte[]> tm = new ConcurrentHashMap<>();
                    for (int k = 0; k < nu; k++) {
                        String uid = in.readUTF();
                        byte[] bytes = new byte[in.readInt()];
                        in.readFully(bytes);
                        tm.put(uid, bytes);
                    }
                    pm.put(type, tm);
                }
                state.put(partition, pm);
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to restore ObjectStoreStateMachine snapshot", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=ObjectStoreStateMachineTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/ObjectStoreStateMachine.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/ObjectStoreStateMachineTest.java
git commit -m "feat(JBTM-4038): add ObjectStoreStateMachine with apply + snapshot"
```

---

### Task 4: `RaftObjectStoreEnvironmentBean`

Property-file-driven config bean, auto-populated by `BeanPopulator`.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftObjectStoreEnvironmentBean.java`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftObjectStoreEnvironmentBeanTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (relied on by Tasks 5 & 6): a bean with these getters/setters (types exact):
  - `String getPartition()/setPartition(String)`
  - `String getRaftId()/setRaftId(String)`
  - `String getMembers()/setMembers(String)` (comma-separated)
  - `String getJgroupsConfig()/setJgroupsConfig(String)` (defaults to `"raft-objectstore-jgroups.xml"`)
  - `String getClusterName()/setClusterName(String)`
  - `String getLogDir()/setLogDir(String)`
  - `long getWriteTimeoutMillis()/setWriteTimeoutMillis(long)` (default `10000`)
  - `boolean isUseFsync()/setUseFsync(boolean)` (default `true`)
  - `String getReadMode()/setReadMode(String)` (default `"linearizable"`)
  - `long getMaxLogSize()/setMaxLogSize(long)` (default `0` = leave RAFT default)

- [ ] **Step 1: Write the failing test**

Create `RaftObjectStoreEnvironmentBeanTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

public class RaftObjectStoreEnvironmentBeanTest {

    @Test
    public void defaultsAreSensible() {
        RaftObjectStoreEnvironmentBean bean = new RaftObjectStoreEnvironmentBean();
        assertEquals("linearizable", bean.getReadMode());
        assertTrue(bean.isUseFsync());
        assertEquals(10000L, bean.getWriteTimeoutMillis());
        assertEquals("raft-objectstore-jgroups.xml", bean.getJgroupsConfig());
    }

    @Test
    public void beanPopulatorProducesNamedInstances() {
        RaftObjectStoreEnvironmentBean bean =
                BeanPopulator.getNamedInstance(RaftObjectStoreEnvironmentBean.class, "stateStore");
        bean.setPartition("stateStore");
        assertEquals("stateStore", bean.getPartition());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftObjectStoreEnvironmentBeanTest
```
Expected: FAIL — the bean class does not exist.

- [ ] **Step 3: Write the implementation**

Create `RaftObjectStoreEnvironmentBean.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.io.File;

import com.arjuna.common.internal.util.propertyservice.PropertyPrefix;

/**
 * Configuration for {@link RaftObjectStore}, populated by BeanPopulator from
 * properties prefixed com.arjuna.ats.arjuna.objectstore.jgroupsraft.
 */
@PropertyPrefix(prefix = "com.arjuna.ats.arjuna.objectstore.jgroupsraft.")
public class RaftObjectStoreEnvironmentBean {

    private volatile String partition = "defaultStore";
    private volatile String raftId;
    private volatile String members;
    private volatile String jgroupsConfig = "raft-objectstore-jgroups.xml";
    private volatile String clusterName = "narayana-raft";
    private volatile String logDir = System.getProperty("user.dir") + File.separator + "RaftObjectStore";
    private volatile long writeTimeoutMillis = 10000L;
    private volatile boolean useFsync = true;
    private volatile String readMode = "linearizable";
    private volatile long maxLogSize = 0L;

    public String getPartition() { return partition; }
    public void setPartition(String partition) { this.partition = partition; }

    public String getRaftId() { return raftId; }
    public void setRaftId(String raftId) { this.raftId = raftId; }

    public String getMembers() { return members; }
    public void setMembers(String members) { this.members = members; }

    public String getJgroupsConfig() { return jgroupsConfig; }
    public void setJgroupsConfig(String jgroupsConfig) { this.jgroupsConfig = jgroupsConfig; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getLogDir() { return logDir; }
    public void setLogDir(String logDir) { this.logDir = logDir; }

    public long getWriteTimeoutMillis() { return writeTimeoutMillis; }
    public void setWriteTimeoutMillis(long writeTimeoutMillis) { this.writeTimeoutMillis = writeTimeoutMillis; }

    public boolean isUseFsync() { return useFsync; }
    public void setUseFsync(boolean useFsync) { this.useFsync = useFsync; }

    public String getReadMode() { return readMode; }
    public void setReadMode(String readMode) { this.readMode = readMode; }

    public long getMaxLogSize() { return maxLogSize; }
    public void setMaxLogSize(long maxLogSize) { this.maxLogSize = maxLogSize; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftObjectStoreEnvironmentBeanTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftObjectStoreEnvironmentBean.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftObjectStoreEnvironmentBeanTest.java
git commit -m "feat(JBTM-4038): add RaftObjectStoreEnvironmentBean config"
```

---

### Task 5: `RaftStoreRuntime` + shared test support

The per-JVM runtime: owns the channel/handle/state-machine, ref-counted and shared across roles, keyed by `raftId` (a JVM hosts exactly one Raft node, so `raftId` identifies its single shared runtime; validated so all roles agree on `clusterName`/`jgroupsConfig` and use distinct `partition`s). Implements the read/write paths including the uncertain-write read-back (§10 item 9). Also introduces the reusable test-support helper.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftStoreRuntime.java`
- Create: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftTestSupport.java`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftStoreRuntimeTest.java`

**Interfaces:**
- Consumes: `ObjectStoreStateMachine`, `StoreCommand`, `RaftObjectStoreEnvironmentBean`.
- Produces (relied on by Tasks 6, 7, 8):
  - `static RaftStoreRuntime acquire(RaftObjectStoreEnvironmentBean bean) throws ObjectStoreException` — get-or-create by `raftId`; registers `bean.getPartition()`; validates; increments ref-count; connects channel on first acquire and (bounded) waits for a leader to exist.
  - `void release(String partition)` — decrement ref-count; last release closes the channel and removes the registry entry.
  - `boolean write(String partition, String typeName, String uid, byte[] state) throws ObjectStoreException`
  - `boolean remove(String partition, String typeName, String uid) throws ObjectStoreException`
  - `byte[] read(String partition, String typeName, String uid) throws ObjectStoreException` (null if absent)
  - `java.util.List<String> scanUids(String partition, String typeName) throws ObjectStoreException`
  - `java.util.List<String> scanTypes(String partition) throws ObjectStoreException`
  - `int currentState(String partition, String typeName, String uid) throws ObjectStoreException`
  - `boolean isLeader()`
  - `org.jgroups.raft.RaftHandle raftHandle()` (for the leadership listener in Task 7 and cluster tests in Task 8)
  - `RaftTestSupport` helper with: `static RaftObjectStoreEnvironmentBean bean(String raftId, String members, String partition)` and `static void awaitLeader(RaftStoreRuntime rt, long millis) throws InterruptedException`.

- [ ] **Step 1: Write the shared test-support helper (no test cycle of its own)**

Create `RaftTestSupport.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftStoreRuntime;

/** Shared helpers for building beans and waiting on leadership in tests. */
public final class RaftTestSupport {

    private RaftTestSupport() { }

    /** A bean pointing at the SHARED_LOOPBACK/InMemoryLog test stack. */
    public static RaftObjectStoreEnvironmentBean bean(String raftId, String members, String partition) {
        RaftObjectStoreEnvironmentBean bean = new RaftObjectStoreEnvironmentBean();
        bean.setJgroupsConfig("raft-objectstore-test.xml");
        bean.setClusterName("test-" + members.replace(",", ""));
        bean.setRaftId(raftId);
        bean.setMembers(members);
        bean.setPartition(partition);
        bean.setWriteTimeoutMillis(5000L);
        return bean;
    }

    public static void awaitLeader(RaftStoreRuntime rt, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!rt.isLeader() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `RaftStoreRuntimeTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftStoreRuntime;

public class RaftStoreRuntimeTest {

    @Test
    public void singleNodeWriteReadRemove() throws Exception {
        RaftStoreRuntime rt = RaftStoreRuntime.acquire(RaftTestSupport.bean("A", "A", "defaultStore"));
        try {
            RaftTestSupport.awaitLeader(rt, 10_000);
            assertTrue(rt.isLeader());

            byte[] state = {5, 6, 7};
            assertTrue(rt.write("defaultStore", "t", "u", state));
            assertArrayEquals(state, rt.read("defaultStore", "t", "u"));
            assertEquals(1, rt.currentState("defaultStore", "t", "u"));

            List<String> uids = rt.scanUids("defaultStore", "t");
            assertEquals(1, uids.size());
            assertTrue(rt.scanTypes("defaultStore").contains("t"));

            assertTrue(rt.remove("defaultStore", "t", "u"));
            assertNull(rt.read("defaultStore", "t", "u"));
        } finally {
            rt.release("defaultStore");
        }
    }

    @Test
    public void rolesShareRuntimeButPartitionsAreIsolated() throws Exception {
        RaftObjectStoreEnvironmentBean action = RaftTestSupport.bean("B", "B", "defaultStore");
        RaftObjectStoreEnvironmentBean state = RaftTestSupport.bean("B", "B", "stateStore");
        RaftStoreRuntime rt1 = RaftStoreRuntime.acquire(action);
        RaftStoreRuntime rt2 = RaftStoreRuntime.acquire(state);
        try {
            assertTrue(rt1 == rt2, "same raftId must share one runtime");
            RaftTestSupport.awaitLeader(rt1, 10_000);
            rt1.write("defaultStore", "sameType", "sameUid", new byte[] {1});
            rt2.write("stateStore", "sameType", "sameUid", new byte[] {2});
            assertArrayEquals(new byte[] {1}, rt1.read("defaultStore", "sameType", "sameUid"));
            assertArrayEquals(new byte[] {2}, rt2.read("stateStore", "sameType", "sameUid"));
        } finally {
            rt1.release("defaultStore");
            rt2.release("stateStore");
        }
    }

    @Test
    public void duplicatePartitionOnSharedRuntimeFailsFast() throws Exception {
        RaftStoreRuntime rt = RaftStoreRuntime.acquire(RaftTestSupport.bean("C", "C", "defaultStore"));
        try {
            assertThrows(ObjectStoreException.class,
                    () -> RaftStoreRuntime.acquire(RaftTestSupport.bean("C", "C", "defaultStore")));
        } finally {
            rt.release("defaultStore");
        }
    }

    @Test
    public void mismatchedClusterNameOnSharedRuntimeFailsFast() throws Exception {
        RaftObjectStoreEnvironmentBean first = RaftTestSupport.bean("D", "D", "defaultStore");
        RaftObjectStoreEnvironmentBean second = RaftTestSupport.bean("D", "D", "stateStore");
        second.setClusterName("a-different-cluster");
        RaftStoreRuntime rt = RaftStoreRuntime.acquire(first);
        try {
            assertThrows(ObjectStoreException.class, () -> RaftStoreRuntime.acquire(second));
        } finally {
            rt.release("defaultStore");
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftStoreRuntimeTest
```
Expected: FAIL — `RaftStoreRuntime` does not exist.

- [ ] **Step 4: Write the implementation**

Create `RaftStoreRuntime.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jgroups.JChannel;
import org.jgroups.protocols.raft.RAFT;
import org.jgroups.raft.Options;
import org.jgroups.raft.RaftHandle;

import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;

/**
 * Per-JVM Raft runtime, keyed by raftId (a JVM hosts exactly one Raft node).
 * Owns one JChannel + RaftHandle + ObjectStoreStateMachine, shared and ref-counted
 * across the &le;3 store roles. Writes go through Raft consensus; reads are
 * leadership-fenced (linearizable) or served from the local applied map (localApplied).
 */
public final class RaftStoreRuntime {

    private static final Map<String, RaftStoreRuntime> RUNTIMES = new HashMap<>();

    private final RaftObjectStoreEnvironmentBean config;
    private final ObjectStoreStateMachine stateMachine = new ObjectStoreStateMachine();
    private final Map<String, Boolean> partitions = new HashMap<>();
    private final boolean linearizable;
    private final long writeTimeout;
    private JChannel channel;
    private RaftHandle raftHandle;
    private int refCount;

    private RaftStoreRuntime(RaftObjectStoreEnvironmentBean config) {
        this.config = config;
        this.linearizable = !"localApplied".equalsIgnoreCase(config.getReadMode());
        this.writeTimeout = config.getWriteTimeoutMillis();
    }

    public static synchronized RaftStoreRuntime acquire(RaftObjectStoreEnvironmentBean bean)
            throws ObjectStoreException {
        String key = bean.getRaftId();
        if (key == null || key.isEmpty()) {
            throw new ObjectStoreException("jgroups-raft object store requires a non-empty raftId");
        }
        RaftStoreRuntime runtime = RUNTIMES.get(key);
        if (runtime == null) {
            runtime = new RaftStoreRuntime(bean);
            runtime.connect();
            RUNTIMES.put(key, runtime);
        } else {
            runtime.validateSharedConfig(bean);
        }
        runtime.registerPartition(bean.getPartition());
        runtime.refCount++;
        return runtime;
    }

    private void validateSharedConfig(RaftObjectStoreEnvironmentBean bean) throws ObjectStoreException {
        if (!config.getClusterName().equals(bean.getClusterName())) {
            throw new ObjectStoreException("roles sharing raftId '" + config.getRaftId()
                    + "' must use the same clusterName; got '" + config.getClusterName()
                    + "' and '" + bean.getClusterName() + "'");
        }
        if (!config.getJgroupsConfig().equals(bean.getJgroupsConfig())) {
            throw new ObjectStoreException("roles sharing raftId '" + config.getRaftId()
                    + "' must use the same jgroupsConfig");
        }
    }

    private void registerPartition(String partition) throws ObjectStoreException {
        if (partition == null || partition.isEmpty()) {
            throw new ObjectStoreException("jgroups-raft object store requires a non-empty partition");
        }
        if (partitions.containsKey(partition)) {
            throw new ObjectStoreException("partition '" + partition
                    + "' is already used by another store sharing raftId '" + config.getRaftId() + "'");
        }
        partitions.put(partition, Boolean.TRUE);
    }

    private void connect() throws ObjectStoreException {
        try {
            channel = new JChannel(config.getJgroupsConfig());
            RAFT raft = channel.getProtocolStack().findProtocol(RAFT.class);
            raft.raftId(config.getRaftId())
                .members(Arrays.asList(config.getMembers().trim().split("\\s*,\\s*")))
                .logDir(config.getLogDir());
            if (config.getMaxLogSize() > 0) {
                raft.maxLogSize(config.getMaxLogSize());
            }
            raftHandle = new RaftHandle(channel, stateMachine);
            channel.connect(config.getClusterName());
        } catch (Exception e) {
            throw new ObjectStoreException("failed to start jgroups-raft channel", e);
        }
    }

    public synchronized void release(String partition) {
        partitions.remove(partition);
        if (--refCount <= 0) {
            RUNTIMES.remove(config.getRaftId());
            if (channel != null) {
                channel.close();
            }
        }
    }

    public boolean isLeader() {
        return raftHandle != null && raftHandle.isLeader();
    }

    public RaftHandle raftHandle() {
        return raftHandle;
    }

    ObjectStoreStateMachine stateMachine() {
        return stateMachine;
    }

    // ---- write path ----

    public boolean write(String partition, String typeName, String uid, byte[] state)
            throws ObjectStoreException {
        return mutate(StoreCommand.write(partition, typeName, uid, state), partition, typeName, uid, true);
    }

    public boolean remove(String partition, String typeName, String uid) throws ObjectStoreException {
        return mutate(StoreCommand.remove(partition, typeName, uid), partition, typeName, uid, false);
    }

    private boolean mutate(StoreCommand cmd, String partition, String typeName, String uid,
                           boolean expectPresentAfter) throws ObjectStoreException {
        byte[] wire = cmd.encode();
        try {
            CompletableFuture<byte[]> future = raftHandle.setAsync(wire, 0, wire.length, Options.DEFAULT_OPTIONS);
            byte[] resp = future.get(writeTimeout, TimeUnit.MILLISECONDS);
            return StoreCommand.decodeBoolean(resp);
        } catch (TimeoutException | ExecutionException e) {
            // Uncertain outcome (§10 item 9): the entry may still commit. Do NOT treat as a
            // clean failure. Linearizably read back what actually landed and report from that.
            byte[] landed = read(partition, typeName, uid);
            boolean present = landed != null;
            if (present == expectPresentAfter) {
                return true;
            }
            throw new ObjectStoreException("uncertain jgroups-raft write for " + partition + "/" + typeName
                    + "/" + uid + "; read-back present=" + present, e);
        } catch (Exception e) {
            throw new ObjectStoreException("jgroups-raft write failed", e);
        }
    }

    // ---- read path ----

    public byte[] read(String partition, String typeName, String uid) throws ObjectStoreException {
        if (!linearizable) {
            return stateMachine.localRead(partition, typeName, uid);
        }
        byte[] resp = query(StoreCommand.read(partition, typeName, uid));
        return StoreCommand.decodeBytesResult(resp);
    }

    public List<String> scanUids(String partition, String typeName) throws ObjectStoreException {
        if (linearizable) {
            barrier(StoreCommand.currentState(partition, typeName, "")); // leadership/read-index barrier
        }
        return stateMachine.localScanUids(partition, typeName);
    }

    public List<String> scanTypes(String partition) throws ObjectStoreException {
        if (linearizable) {
            barrier(StoreCommand.currentState(partition, "", ""));
        }
        return stateMachine.localScanTypes(partition);
    }

    public int currentState(String partition, String typeName, String uid) throws ObjectStoreException {
        if (!linearizable) {
            return stateMachine.localCurrentState(partition, typeName, uid);
        }
        return StoreCommand.decodeInt(query(StoreCommand.currentState(partition, typeName, uid)));
    }

    private byte[] query(StoreCommand cmd) throws ObjectStoreException {
        byte[] wire = cmd.encode();
        try {
            return raftHandle.getAsync(wire, 0, wire.length, Options.DEFAULT_OPTIONS)
                    .get(writeTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new ObjectStoreException("jgroups-raft read failed", e);
        }
    }

    /** Lightweight linearizable leadership/read-index confirmation before an off-loop scan. */
    private void barrier(StoreCommand cmd) throws ObjectStoreException {
        query(cmd);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftStoreRuntimeTest
```
Expected: PASS (all four tests).

- [ ] **Step 6: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftStoreRuntime.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftTestSupport.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftStoreRuntimeTest.java
git commit -m "feat(JBTM-4038): add RaftStoreRuntime (channel lifecycle, read/write, validation)"
```

---

### Task 6: `RaftObjectStore` (the `ObjectStoreAPI` adaptor) + store-contract test

The Narayana-facing, committed-only adaptor. Mirrors `SlotStoreAdaptor`: supported committed ops delegate to the runtime; unsupported ops throw. The store-contract test mirrors `com.hp.mwtests.ts.arjuna.objectstore.ObjectStoreTest`.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftObjectStore.java`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftObjectStoreContractTest.java`

**Interfaces:**
- Consumes: `RaftStoreRuntime`, `RaftObjectStoreEnvironmentBean`, `RaftTestSupport`.
- Produces (relied on by Tasks 8 & 9): `public class RaftObjectStore implements ObjectStoreAPI` with:
  - `RaftObjectStore(RaftObjectStoreEnvironmentBean bean)` (the ctor `StoreManager` selects — its parameter type name ends with `EnvironmentBean`)
  - `RaftObjectStore()` no-arg convenience (uses `BeanPopulator.getDefaultInstance(RaftObjectStoreEnvironmentBean.class)`)

- [ ] **Step 1: Write the failing test**

Create `RaftObjectStoreContractTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.objectstore.StateType;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore;

public class RaftObjectStoreContractTest {

    private RaftObjectStore store;

    @BeforeEach
    public void setUp() throws Exception {
        store = new RaftObjectStore(RaftTestSupport.bean("A", "A", "defaultStore"));
        store.start();
        // block until this single-member node is leader (writes/reads serviceable)
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                store.write_committed(new Uid(), "/warmup", new OutputObjectState());
                break;
            } catch (ObjectStoreException retry) {
                Thread.sleep(50);
            }
        }
    }

    @AfterEach
    public void tearDown() {
        if (store != null) {
            store.stop();
        }
    }

    @Test
    public void writeReadRemoveCommitted() throws Exception {
        Uid u = new Uid();
        String tn = "/StateManager/AtomicAction";
        OutputObjectState buff = new OutputObjectState();
        buff.packString("payload");

        assertTrue(store.write_committed(u, tn, buff));
        InputObjectState read = store.read_committed(u, tn);
        assertNotNull(read);
        assertEquals("payload", read.unpackString());
        assertEquals(StateStatus.OS_COMMITTED, store.currentState(u, tn));

        assertTrue(store.remove_committed(u, tn));
        assertEquals(StateStatus.OS_UNKNOWN, store.currentState(u, tn));
    }

    @Test
    public void allObjUidsAndAllTypes() throws Exception {
        String fooType = "/StateManager/LockManager/foo";
        String barType = "/StateManager/LockManager/bar";
        for (int i = 0; i < 5; i++) {
            store.write_committed(new Uid(), fooType, new OutputObjectState());
        }
        for (int i = 0; i < 3; i++) {
            store.write_committed(new Uid(), barType, new OutputObjectState());
        }

        assertEquals(5, uidsOf(fooType).size());
        assertEquals(3, uidsOf(barType).size());

        Collection<String> types = typesOf();
        assertTrue(types.contains(fooType));
        assertTrue(types.contains(barType));
    }

    @Test
    public void unsupportedOperationsThrow() {
        Uid u = new Uid();
        assertThrows(ObjectStoreException.class, () -> store.write_uncommitted(u, "t", new OutputObjectState()));
        assertThrows(ObjectStoreException.class, () -> store.read_uncommitted(u, "t"));
        assertThrows(ObjectStoreException.class, () -> store.remove_uncommitted(u, "t"));
        assertThrows(ObjectStoreException.class, () -> store.commit_state(u, "t"));
        assertThrows(ObjectStoreException.class, () -> store.hide_state(u, "t"));
        assertThrows(ObjectStoreException.class, () -> store.reveal_state(u, "t"));
        assertThrows(ObjectStoreException.class, () -> store.isType(u, "t", StateType.OS_SHARED));
    }

    @Test
    public void fullCommitNotNeededAndSyncIsNoOp() throws Exception {
        assertFalse(store.fullCommitNeeded());
        store.sync();
    }

    private Collection<Uid> uidsOf(String type) throws Exception {
        Collection<Uid> uids = new ArrayList<>();
        InputObjectState ios = new InputObjectState();
        assertTrue(store.allObjUids(type, ios));
        while (true) {
            Uid uid = UidHelper.unpackFrom(ios);
            if (uid.equals(Uid.nullUid())) {
                break;
            }
            uids.add(uid);
        }
        return uids;
    }

    private Collection<String> typesOf() throws Exception {
        Collection<String> types = new ArrayList<>();
        InputObjectState ios = new InputObjectState();
        assertTrue(store.allTypes(ios));
        while (true) {
            String t = ios.unpackString();
            if (t.length() == 0) {
                break;
            }
            types.add(t);
        }
        return types;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftObjectStoreContractTest
```
Expected: FAIL — `RaftObjectStore` does not exist.

- [ ] **Step 3: Write the implementation**

Create `RaftObjectStore.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.io.IOException;
import java.io.SyncFailedException;
import java.util.List;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.objectstore.ObjectStoreAPI;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

/**
 * Committed-only ObjectStoreAPI backed by a replicated jgroups-raft log.
 * Supported: write/read/remove_committed, allObjUids (both), allTypes, currentState,
 * sync (no-op), getStoreName, start, stop, fullCommitNeeded()==false.
 * Unsupported (shadow/uncommitted/hide/reveal/isType) throw, as in SlotStoreAdaptor.
 */
public class RaftObjectStore implements ObjectStoreAPI {

    private final RaftObjectStoreEnvironmentBean config;
    private final String partition;
    private RaftStoreRuntime runtime;

    public RaftObjectStore() {
        this(BeanPopulator.getDefaultInstance(RaftObjectStoreEnvironmentBean.class));
    }

    public RaftObjectStore(RaftObjectStoreEnvironmentBean config) {
        this.config = config;
        this.partition = config.getPartition();
    }

    @Override
    public void start() {
        try {
            runtime = RaftStoreRuntime.acquire(config);
        } catch (ObjectStoreException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() {
        if (runtime != null) {
            runtime.release(partition);
            runtime = null;
        }
    }

    @Override
    public String getStoreName() {
        return "RaftObjectStore:" + partition;
    }

    @Override
    public boolean write_committed(Uid u, String tn, OutputObjectState buff) throws ObjectStoreException {
        return runtime.write(partition, tn, u.stringForm(), buff.buffer());
    }

    @Override
    public InputObjectState read_committed(Uid u, String tn) throws ObjectStoreException {
        byte[] state = runtime.read(partition, tn, u.stringForm());
        if (state == null) {
            return null;
        }
        return new InputObjectState(u, tn, state);
    }

    @Override
    public boolean remove_committed(Uid u, String tn) throws ObjectStoreException {
        return runtime.remove(partition, tn, u.stringForm());
    }

    @Override
    public boolean allObjUids(String typeName, InputObjectState foundInstances, int matchState)
            throws ObjectStoreException {
        List<String> uids = runtime.scanUids(partition, typeName);
        OutputObjectState buffer = new OutputObjectState();
        try {
            for (String uid : uids) {
                UidHelper.packInto(new Uid(uid), buffer);
            }
            UidHelper.packInto(Uid.nullUid(), buffer);
        } catch (IOException e) {
            throw new ObjectStoreException(e);
        }
        foundInstances.setBuffer(buffer.buffer());
        return true;
    }

    @Override
    public boolean allObjUids(String typeName, InputObjectState foundInstances) throws ObjectStoreException {
        return allObjUids(typeName, foundInstances, StateStatus.OS_UNKNOWN);
    }

    @Override
    public boolean allTypes(InputObjectState foundTypes) throws ObjectStoreException {
        List<String> types = runtime.scanTypes(partition);
        OutputObjectState buffer = new OutputObjectState();
        try {
            for (String type : types) {
                buffer.packString(type);
            }
            buffer.packString("");
        } catch (IOException e) {
            throw new ObjectStoreException(e);
        }
        foundTypes.setBuffer(buffer.buffer());
        return true;
    }

    @Override
    public int currentState(Uid u, String tn) throws ObjectStoreException {
        return runtime.currentState(partition, tn, u.stringForm());
    }

    @Override
    public void sync() throws SyncFailedException, ObjectStoreException {
        // no-op: durability is guaranteed at write time by majority commit
    }

    @Override
    public boolean fullCommitNeeded() {
        return false;
    }

    // ---- unsupported (committed-only store) ----

    @Override
    public boolean commit_state(Uid u, String tn) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public InputObjectState read_uncommitted(Uid u, String tn) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public boolean remove_uncommitted(Uid u, String tn) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public boolean write_uncommitted(Uid u, String tn, OutputObjectState buff) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public boolean hide_state(Uid u, String tn) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public boolean reveal_state(Uid u, String tn) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }

    @Override
    public boolean isType(Uid u, String tn, int st) throws ObjectStoreException {
        throw new ObjectStoreException(tsLogger.i18NLogger.get_method_not_implemented());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftObjectStoreContractTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftObjectStore.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftObjectStoreContractTest.java
git commit -m "feat(JBTM-4038): add RaftObjectStore committed-only ObjectStoreAPI adaptor"
```

---

### Task 7: Leadership-gated recovery (`RecoveryControl` + `RaftRecoveryLeadershipListener`)

Bridges Raft `RoleChange` to `RecoveryManager` suspend/resume, off the event-loop thread, with a caught-up wait before resuming. The `RecoveryControl` seam makes the decision logic unit-testable without a live `RecoveryManager`.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RecoveryControl.java`
- Create: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftRecoveryLeadershipListener.java`
- Modify: `ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftStoreRuntime.java` (register the listener on connect)
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftRecoveryLeadershipListenerTest.java`

**Interfaces:**
- Consumes: `RaftStoreRuntime` (for `raftHandle()`), `org.jgroups.protocols.raft.RAFT.RoleChange`, `org.jgroups.protocols.raft.Role`.
- Produces:
  - `interface RecoveryControl { void suspend(); void resume(); }`
  - `class RecoveryManagerControl implements RecoveryControl` (default; calls `RecoveryManager.manager().suspend(true)` / `.resume()`)
  - `class RaftRecoveryLeadershipListener implements RAFT.RoleChange`, ctor `RaftRecoveryLeadershipListener(RecoveryControl control, java.util.function.BooleanSupplier caughtUp)`; `void roleChanged(Role role)`; `void close()` (shuts the executor down). For tests: a package-visible `void awaitQuiescence(long millis)` that blocks until the executor has drained.

- [ ] **Step 1: Write the failing test**

Create `RaftRecoveryLeadershipListenerTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.jgroups.protocols.raft.Role;
import org.junit.jupiter.api.Test;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftRecoveryLeadershipListener;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RecoveryControl;

public class RaftRecoveryLeadershipListenerTest {

    static final class CountingControl implements RecoveryControl {
        final AtomicInteger suspends = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();
        public void suspend() { suspends.incrementAndGet(); }
        public void resume() { resumes.incrementAndGet(); }
    }

    @Test
    public void becomingLeaderResumesAfterCaughtUp() throws Exception {
        CountingControl control = new CountingControl();
        RaftRecoveryLeadershipListener listener =
                new RaftRecoveryLeadershipListener(control, () -> true);
        try {
            listener.roleChanged(Role.Leader);
            listener.awaitQuiescence(5000);
            assertEquals(1, control.resumes.get());
            assertEquals(0, control.suspends.get());
        } finally {
            listener.close();
        }
    }

    @Test
    public void losingLeadershipSuspends() throws Exception {
        CountingControl control = new CountingControl();
        RaftRecoveryLeadershipListener listener =
                new RaftRecoveryLeadershipListener(control, () -> true);
        try {
            listener.roleChanged(Role.Follower);
            listener.awaitQuiescence(5000);
            assertEquals(1, control.suspends.get());
            assertEquals(0, control.resumes.get());
        } finally {
            listener.close();
        }
    }

    @Test
    public void learnerRoleAlsoSuspends() throws Exception {
        CountingControl control = new CountingControl();
        RaftRecoveryLeadershipListener listener =
                new RaftRecoveryLeadershipListener(control, () -> true);
        try {
            listener.roleChanged(Role.Learner);
            listener.awaitQuiescence(5000);
            assertEquals(1, control.suspends.get());
        } finally {
            listener.close();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftRecoveryLeadershipListenerTest
```
Expected: FAIL — the classes do not exist.

- [ ] **Step 3: Write the `RecoveryControl` seam**

Create `RecoveryControl.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import com.arjuna.ats.arjuna.recovery.RecoveryManager;

/** Seam over Narayana's RecoveryManager so leadership handling is unit-testable. */
public interface RecoveryControl {

    void suspend();

    void resume();

    /** Production implementation: drives the in-process PeriodicRecovery. */
    final class RecoveryManagerControl implements RecoveryControl {
        @Override
        public void suspend() {
            // async: do not wait for an in-progress scan (its tail is covered by idempotent recovery)
            RecoveryManager.manager().suspend(true);
        }

        @Override
        public void resume() {
            RecoveryManager.manager().resume();
        }
    }
}
```

- [ ] **Step 4: Write the listener**

Create `RaftRecoveryLeadershipListener.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jgroups.protocols.raft.RAFT;
import org.jgroups.protocols.raft.Role;

/**
 * Bridges Raft leadership changes to RecoveryManager suspend/resume.
 * roleChanged runs on the RAFT event-loop thread, so it must not block: it only
 * hands the work to a single-thread executor and returns immediately.
 */
public final class RaftRecoveryLeadershipListener implements RAFT.RoleChange {

    private final RecoveryControl control;
    private final BooleanSupplier caughtUp;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "raft-recovery-leadership");
                t.setDaemon(true);
                return t;
            });

    public RaftRecoveryLeadershipListener(RecoveryControl control, BooleanSupplier caughtUp) {
        this.control = control;
        this.caughtUp = caughtUp;
    }

    @Override
    public void roleChanged(Role role) {
        if (role == Role.Leader) {
            executor.execute(() -> {
                // Wait until this new leader's state machine has reached its term's commit point
                // (after the initial no-op) before recovering, so scans never miss committed logs.
                long deadline = System.currentTimeMillis() + 30_000;
                while (!caughtUp.getAsBoolean() && System.currentTimeMillis() < deadline) {
                    sleep();
                }
                control.resume();
            });
        } else {
            // Follower or Learner: stop recovering.
            executor.execute(control::suspend);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test hook: block until all submitted role-change tasks have run. */
    void awaitQuiescence(long millis) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        executor.execute(latch::countDown);
        latch.await(millis, TimeUnit.MILLISECONDS);
    }

    public void close() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 5: Register the listener in `RaftStoreRuntime`**

In `RaftStoreRuntime.java`, add a field and register the listener when connecting, and close it on shutdown.

Add the field (next to `private RaftHandle raftHandle;`):
```java
    private RaftRecoveryLeadershipListener leadershipListener;
```

In `connect()`, after `raftHandle = new RaftHandle(channel, stateMachine);` and before `channel.connect(...)`, add:
```java
            leadershipListener = new RaftRecoveryLeadershipListener(
                    new RecoveryControl.RecoveryManagerControl(),
                    () -> raftHandle.isLeader() && raftHandle.lastApplied() >= raftHandle.commitIndex());
            raftHandle.addRoleListener(leadershipListener);
```

In `release(String)`, inside the `if (--refCount <= 0)` block, before `channel.close();`, add:
```java
            if (leadershipListener != null) {
                leadershipListener.close();
            }
```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftRecoveryLeadershipListenerTest,RaftStoreRuntimeTest
```
Expected: PASS (listener unit tests + runtime tests still green with the listener wired in).

- [ ] **Step 7: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RecoveryControl.java \
        ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftRecoveryLeadershipListener.java \
        ArjunaCore/jgroups-raft-objectstore/classes/com/arjuna/ats/internal/arjuna/objectstore/jgroupsraft/RaftStoreRuntime.java \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftRecoveryLeadershipListenerTest.java
git commit -m "feat(JBTM-4038): leadership-gated recovery bridge (RoleChange -> RecoveryManager)"
```

---

### Task 8: Multi-node cluster integration tests

A 3-node cluster in one JVM (SHARED_LOOPBACK). Covers replication + failover, idempotent retry, and bootstrapping-before-quorum. Test-only deliverable, worth its own reviewer gate.

**Files:**
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftClusterTest.java`

**Interfaces:**
- Consumes: `RaftStoreRuntime`, `RaftObjectStoreEnvironmentBean`, `RaftTestSupport`.
- Produces: nothing (tests).

- [ ] **Step 1: Write the failing cluster test**

Create `RaftClusterTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftStoreRuntime;

public class RaftClusterTest {

    private final List<RaftStoreRuntime> nodes = new ArrayList<>();

    private RaftStoreRuntime node(String raftId, String members) throws Exception {
        RaftObjectStoreEnvironmentBean bean = RaftTestSupport.bean(raftId, members, "defaultStore");
        RaftStoreRuntime rt = RaftStoreRuntime.acquire(bean);
        nodes.add(rt);
        return rt;
    }

    private RaftStoreRuntime leaderOf(List<RaftStoreRuntime> live, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            for (RaftStoreRuntime rt : live) {
                if (rt.isLeader()) {
                    return rt;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    @AfterEach
    public void tearDown() {
        for (RaftStoreRuntime rt : nodes) {
            try {
                rt.release("defaultStore");
            } catch (RuntimeException ignore) {
                // node may already be closed by a failover test
            }
        }
        nodes.clear();
    }

    @Test
    public void writeOnOneReadOnAnotherThenFailover() throws Exception {
        RaftStoreRuntime a = node("A", "A,B,C");
        RaftStoreRuntime b = node("B", "A,B,C");
        RaftStoreRuntime c = node("C", "A,B,C");

        RaftStoreRuntime leader = leaderOf(List.of(a, b, c), 20_000);
        assertTrue(leader != null, "a leader should be elected");

        byte[] state = {4, 2};
        assertTrue(leader.write("defaultStore", "/txn", "uid-x", state));

        // read from every node (reads are REDIRECTed/linearizable)
        assertArrayEquals(state, a.read("defaultStore", "/txn", "uid-x"));
        assertArrayEquals(state, b.read("defaultStore", "/txn", "uid-x"));
        assertArrayEquals(state, c.read("defaultStore", "/txn", "uid-x"));

        // kill the leader; a new leader among the remaining majority must still have the data
        List<RaftStoreRuntime> survivors = new ArrayList<>(List.of(a, b, c));
        survivors.remove(leader);
        leader.release("defaultStore");

        RaftStoreRuntime newLeader = leaderOf(survivors, 20_000);
        assertTrue(newLeader != null, "a new leader should be elected from the majority");
        assertArrayEquals(state, newLeader.read("defaultStore", "/txn", "uid-x"));
    }

    @Test
    public void duplicateWriteIsIdempotent() throws Exception {
        RaftStoreRuntime a = node("A", "A,B,C");
        RaftStoreRuntime b = node("B", "A,B,C");
        RaftStoreRuntime c = node("C", "A,B,C");
        RaftStoreRuntime leader = leaderOf(List.of(a, b, c), 20_000);
        assertTrue(leader != null);

        byte[] state = {7};
        assertTrue(leader.write("defaultStore", "/txn", "dup", state));
        assertTrue(leader.write("defaultStore", "/txn", "dup", state)); // re-delivery
        assertArrayEquals(state, leader.read("defaultStore", "/txn", "dup"));
        assertTrue(leader.scanUids("defaultStore", "/txn").size() == 1, "duplicate write must not create two entries");
    }

    @Test
    public void bootstrappingBeforeQuorumIsUnavailableThenSucceeds() throws Exception {
        // one node of a 3-member cluster: no majority, so no leader and writes are unavailable.
        // Use a short write timeout so the "unavailable" assertion is fast.
        RaftObjectStoreEnvironmentBean beanA = RaftTestSupport.bean("A", "A,B,C", "defaultStore");
        beanA.setWriteTimeoutMillis(1500L);
        RaftStoreRuntime a = RaftStoreRuntime.acquire(beanA);
        nodes.add(a);

        RaftTestSupport.awaitLeader(a, 3_000);
        assertTrue(!a.isLeader(), "a single node of a 3-member cluster cannot be leader");

        // writing without quorum must not silently succeed
        try {
            a.write("defaultStore", "/txn", "early", new byte[] {1});
            fail("write must not commit without a quorum");
        } catch (ObjectStoreException expected) {
            // expected: uncertain/failed write with no quorum
        }

        // bring up a second node -> quorum (2 of 3) -> a leader emerges and writes succeed
        RaftStoreRuntime b = node("B", "A,B,C");
        RaftStoreRuntime leader = leaderOf(List.of(a, b), 20_000);
        assertTrue(leader != null, "quorum of 2/3 should elect a leader");
        assertTrue(leader.write("defaultStore", "/txn", "later", new byte[] {2}));
    }
}
```

- [ ] **Step 2: Run test to verify it fails, then passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftClusterTest
```
Expected: FAIL first only if the runtime has a defect surfaced by multi-node use (e.g. leadership/timing). All three tests must end PASS. If `writeOnOneReadOnAnotherThenFailover` is flaky on failover timing, raise the `leaderOf` timeout — do not weaken the assertions.

- [ ] **Step 3: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftClusterTest.java
git commit -m "test(JBTM-4038): multi-node cluster replication, failover, idempotency, bootstrap"
```

---

### Task 9: Default production config, three-role wiring docs, and full-module verification

Ships the default production JGroups+RAFT stack, documents the operator wiring (all three roles + the `writeOptimisation=true` state-store requirement + the version-pinning note), and verifies the whole module builds and all tests pass together.

**Files:**
- Create: `ArjunaCore/jgroups-raft-objectstore/etc/raft-objectstore-jgroups.xml`
- Create: `ArjunaCore/jgroups-raft-objectstore/README.md`
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/DefaultConfigResourceTest.java`

**Interfaces:**
- Consumes: `RaftObjectStoreEnvironmentBean` (its default `jgroupsConfig` name).
- Produces: the shipped default config resource `raft-objectstore-jgroups.xml`.

- [ ] **Step 1: Write the failing config-resource test**

Create `DefaultConfigResourceTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.jgroups.JChannel;
import org.jgroups.protocols.raft.RAFT;
import org.jgroups.protocols.raft.REDIRECT;
import org.jgroups.protocols.raft.election.BaseElection;
import org.junit.jupiter.api.Test;

import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStoreEnvironmentBean;

public class DefaultConfigResourceTest {

    @Test
    public void defaultResourceNameMatchesBeanDefault() {
        assertEquals("raft-objectstore-jgroups.xml", new RaftObjectStoreEnvironmentBean().getJgroupsConfig());
    }

    @Test
    public void shippedStackIsPresentAndParsesWithRaftProtocols() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("raft-objectstore-jgroups.xml")) {
            assertNotNull(in, "raft-objectstore-jgroups.xml must be on the classpath");
        }
        JChannel ch = new JChannel("raft-objectstore-jgroups.xml");
        try {
            assertNotNull(ch.getProtocolStack().findProtocol(RAFT.class), "stack must include raft.RAFT");
            assertNotNull(ch.getProtocolStack().findProtocol(REDIRECT.class), "stack must include raft.REDIRECT");
            assertNotNull(ch.getProtocolStack().findProtocol(BaseElection.class), "stack must include raft.ELECTION");
        } finally {
            ch.close();
        }
    }
}
```

> If `org.jgroups.protocols.raft.election.BaseElection` is not the concrete type on the classpath, assert on `org.jgroups.protocols.raft.ELECTION` instead — run `mvn dependency:tree` / check the jgroups-raft jar to confirm the election protocol class name, then use that exact type.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=DefaultConfigResourceTest
```
Expected: FAIL — `raft-objectstore-jgroups.xml` is not on the classpath.

- [ ] **Step 3: Create the default production stack**

Create `ArjunaCore/jgroups-raft-objectstore/etc/raft-objectstore-jgroups.xml` (TCP + real discovery + `FileBasedLog`; `raft_id`/`members`/`log_dir` are overridden programmatically by `RaftStoreRuntime`, with `${…}` defaults so the file is usable standalone):
```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    <TCP bind_addr="${jgroups.bind_addr:127.0.0.1}"
         bind_port="${jgroups.tcp.port:7800}"/>
    <TCPPING async_discovery="true"
             initial_hosts="${jgroups.tcpping.initial_hosts:127.0.0.1[7800],127.0.0.1[7801],127.0.0.1[7802]}"
             port_range="2"/>
    <MERGE3 min_interval="10000" max_interval="30000"/>
    <FD_SOCK/>
    <FD_ALL3 timeout="9000" interval="3000"/>
    <VERIFY_SUSPECT timeout="1500"/>
    <pbcast.NAKACK2 use_mcast_xmit="false"/>
    <UNICAST3/>
    <pbcast.STABLE desired_avg_gossip="50000" max_bytes="4M"/>
    <pbcast.GMS print_local_addr="true" join_timeout="2000"/>
    <FRAG4 frag_size="60K"/>
    <raft.ELECTION/>
    <raft.RAFT members="${raft_members:A,B,C}"
               raft_id="${raft_id:A}"
               log_class="org.jgroups.protocols.raft.FileBasedLog"
               log_dir="${log_dir:RaftObjectStore}"/>
    <raft.REDIRECT/>
</config>
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=DefaultConfigResourceTest
```
Expected: PASS.

- [ ] **Step 5: Write the operator wiring documentation**

Create `ArjunaCore/jgroups-raft-objectstore/README.md`:
```markdown
# jgroups-raft Object Store (JBTM-4038)

A committed-only Narayana `ObjectStoreAPI` that replicates transaction logs across an
embedded jgroups-raft cluster, giving high availability of transaction logs with
leader-only, idempotent recovery.

## Enabling it (all three store roles)

    com.arjuna.ats.arjuna.objectstore.objectStoreType                    = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore
    com.arjuna.ats.arjuna.objectstore.stateStore.objectStoreType         = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore
    com.arjuna.ats.arjuna.objectstore.communicationStore.objectStoreType = com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftObjectStore

    # distinct partition per role (must differ; roles otherwise share one Raft group)
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.partition                    = defaultStore
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.stateStore.partition         = stateStore
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.communicationStore.partition = communicationStore

    # cluster identity (same clusterName/jgroupsConfig across the three roles; unique raftId per JVM)
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.raftId       = A
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.members      = A,B,C
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.clusterName  = narayana-raft
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.jgroupsConfig= raft-objectstore-jgroups.xml
    com.arjuna.ats.arjuna.objectstore.jgroupsraft.logDir       = /var/narayana/raft

### State-store role additionally requires

    com.arjuna.ats.arjuna.coordinator.transactionLog.writeOptimisation = true

Without it, `topLevelPrepare()` falls through to `write_uncommitted`, which this
committed-only store rejects. Pure JTA is unaffected.

## Read consistency

`readMode=linearizable` (default) fences reads through the leader (safe for all roles,
including the state store). `readMode=localApplied` reads the local applied map without a
Raft round-trip — lower latency but not zombie-leader-fenced; use it only for the
action/communication roles.

## Operational notes

- The store is unavailable until a majority of `members` is reachable and a leader is
  elected (2 of 3, 3 of 5). Until then, writes are uncertain/unavailable and reads fail.
- Recovery runs only on the current leader (leader-only recovery); the rare
  demoted-unaware-leader window is bounded by JGroups failure detection and kept safe by
  write/read fencing plus Narayana's idempotent recovery.

## Version note

jgroups-raft is currently `2.0.0.Final-SNAPSHOT`. Pin/track a released version before
shipping (see spec §13).
```

- [ ] **Step 6: Run the full module test suite**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore -am test
```
Expected: BUILD SUCCESS; all tests from Tasks 1–9 pass together.

- [ ] **Step 7: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/etc/raft-objectstore-jgroups.xml \
        ArjunaCore/jgroups-raft-objectstore/README.md \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/DefaultConfigResourceTest.java
git commit -m "feat(JBTM-4038): ship default JGroups stack + operator wiring docs"
```

---

### Task 10 (optional, qa-suite level): End-to-end JTA recovery across the cluster

The spec (§11) marks this "higher effort, likely qa-suite level." It exercises a real `AtomicAction` written to the replicated store, crashes the owning node, and asserts a surviving leader can enumerate and drive the in-doubt transaction. Include it if the working branch can depend on `arjunacore` recovery wiring; otherwise track it as a follow-up in the qa suite.

**Files:**
- Modify: `ArjunaCore/jgroups-raft-objectstore/pom.xml` (add `org.jboss.narayana.arjunacore:arjunacore` test dependency for `AtomicAction`/`RecoveryManager` wiring)
- Test: `ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftEndToEndRecoveryTest.java`

**Interfaces:**
- Consumes: `RaftStoreRuntime`, `RaftObjectStore`, `RaftTestSupport`.
- Produces: nothing (test).

- [ ] **Step 1: Add the test dependency**

In `ArjunaCore/jgroups-raft-objectstore/pom.xml`, add inside `<dependencies>`:
```xml
    <dependency>
      <groupId>org.jboss.narayana.arjunacore</groupId>
      <artifactId>arjunacore</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Write the failing end-to-end test**

Create `RaftEndToEndRecoveryTest.java`:
```java
/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.hp.mwtests.ts.arjuna.objectstore.jgroupsraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.internal.arjuna.objectstore.jgroupsraft.RaftStoreRuntime;

/**
 * Simulates an in-doubt transaction log written by the owning node, then that node
 * crashing, and asserts a surviving leader can enumerate the in-doubt record from the
 * replicated log (the core HA-of-transaction-logs guarantee).
 */
public class RaftEndToEndRecoveryTest {

    private final List<RaftStoreRuntime> nodes = new ArrayList<>();

    private RaftStoreRuntime node(String raftId) throws Exception {
        RaftStoreRuntime rt = RaftStoreRuntime.acquire(RaftTestSupport.bean(raftId, "A,B,C", "defaultStore"));
        nodes.add(rt);
        return rt;
    }

    private RaftStoreRuntime leaderOf(List<RaftStoreRuntime> live, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            for (RaftStoreRuntime rt : live) {
                if (rt.isLeader()) {
                    return rt;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    @AfterEach
    public void tearDown() {
        for (RaftStoreRuntime rt : nodes) {
            try {
                rt.release("defaultStore");
            } catch (RuntimeException ignore) {
            }
        }
        nodes.clear();
    }

    @Test
    public void survivorSeesInDoubtLogAfterOwnerCrash() throws Exception {
        RaftStoreRuntime a = node("A");
        RaftStoreRuntime b = node("B");
        RaftStoreRuntime c = node("C");
        RaftStoreRuntime leader = leaderOf(List.of(a, b, c), 20_000);
        assertTrue(leader != null);

        // owning node writes an in-doubt AtomicAction-style record
        Uid txn = new Uid();
        String type = "/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction";
        OutputObjectState log = new OutputObjectState();
        log.packString("prepared-participant-list");
        assertTrue(leader.write("defaultStore", type, txn.stringForm(), log.buffer()));

        // crash the owner
        List<RaftStoreRuntime> survivors = new ArrayList<>(List.of(a, b, c));
        survivors.remove(leader);
        leader.release("defaultStore");

        // a surviving leader must be able to enumerate and read the in-doubt record
        RaftStoreRuntime newLeader = leaderOf(survivors, 20_000);
        assertTrue(newLeader != null);
        List<String> uids = newLeader.scanUids("defaultStore", type);
        assertTrue(uids.contains(txn.stringForm()), "survivor must see the crashed owner's in-doubt log");

        byte[] recoveredBytes = newLeader.read("defaultStore", type, txn.stringForm());
        assertNotNull(recoveredBytes, "survivor must be able to read the in-doubt log content");
        InputObjectState recovered = new InputObjectState(txn, type, recoveredBytes);
        assertEquals("prepared-participant-list", recovered.unpackString());
    }
}
```

- [ ] **Step 3: Run test to verify it fails, then passes**

Run:
```bash
cd /Users/jfinelli/workspace/github.com/jmfinelli/JBTM-4038/claudeImplementation/narayana
mvn -q -pl ArjunaCore/jgroups-raft-objectstore test -Dtest=RaftEndToEndRecoveryTest
```
Expected: PASS (fails first if the arjunacore test dependency or failover timing is wrong).

- [ ] **Step 4: Commit**

```bash
git add ArjunaCore/jgroups-raft-objectstore/pom.xml \
        ArjunaCore/jgroups-raft-objectstore/tests/classes/com/hp/mwtests/ts/arjuna/objectstore/jgroupsraft/RaftEndToEndRecoveryTest.java
git commit -m "test(JBTM-4038): end-to-end in-doubt log survival across owner crash"
```

---

## Spec coverage map

| Spec section / requirement | Task(s) |
|---|---|
| §2 drop-in `ObjectStoreAPI` by config | 6 (adaptor), 9 (wiring) |
| §2 replicate action store; survivor completes in-doubt | 8, 10 |
| §2 serve all three roles | 6, 9 (partition per role) |
| §2/§8 leader-only, idempotent recovery | 7 (bridge), 8 (idempotency), 10 |
| §4/§7.1 shared runtime, partition key-space, fail-fast | 5 |
| §6 `RaftObjectStore` committed-only + unsupported throw | 6 |
| §6 `RaftObjectStoreEnvironmentBean` | 4 |
| §6 `RaftStoreRuntime` | 5, 7 |
| §6 `ObjectStoreStateMachine` (apply + snapshot) | 3 |
| §6 `StoreCommand` codec | 2 |
| §6 `RaftRecoveryLeadershipListener` | 7 |
| §7.4 wire format (mutating + read-only opcodes) | 2, 3 |
| §7.5 write path (setAsync, majority commit) | 5, 8 |
| §7.6 read path (linearizable / localApplied) | 5 |
| §7.7 sync no-op | 6 |
| §7.8 durability, restart, `start()` bootstrapping | 5 (connect), 8 (bootstrap), 9 (FileBasedLog stack) |
| §8/§8.1 leadership-gated recovery + caught-up wait | 7 |
| §9 configuration + shared-runtime/partition constraints | 4, 5, 9 |
| §10.1/3/9 uncertain write read-back | 5 |
| §10.2/10 quorum lost / bootstrap unavailable | 8 |
| §10.4 post-election caught-up read correctness | 7 |
| §10.5 snapshot consistency | 3 |
| §10.6 state-store `writeOptimisation` caveat | 9 (README) |
| §11 store-contract test | 6 |
| §11 codec + snapshot round-trip tests | 2, 3 |
| §11 multi-node cluster / failover test | 8 |
| §11 leader-only + overlapping-recovery safety | 7 (unit), 8 (idempotency) |
| §11 partition isolation test | 3, 5 |
| §11 idempotent-retry test | 8 |
| §11 uncertain-write test | 5 |
| §11 bootstrapping test | 8 |
| §11 end-to-end JTA recovery | 10 |
| §13 build integration (module, dep scoping, version note) | 1, 9 (README note) |
