/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * (C) 2005-2006,
 * @author JBoss Inc.
 */
package com.hp.mwtests.ts.arjuna.recovery;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.coordinator.AbstractRecord;
import com.arjuna.ats.arjuna.coordinator.ActionStatus;
import com.arjuna.ats.arjuna.coordinator.RecordType;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeManager;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeMap;
import com.arjuna.ats.arjuna.recovery.RecoveryScan;
import com.arjuna.ats.arjuna.tools.osb.mbean.ActionBean;
import com.arjuna.ats.arjuna.tools.osb.mbean.LogRecordWrapper;
import com.arjuna.ats.arjuna.tools.osb.mbean.OSEntryBean;
import com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser;
import com.arjuna.ats.arjuna.tools.osb.mbean.UidWrapper;
import com.arjuna.ats.internal.arjuna.recovery.AtomicActionRecoveryModule;
import com.arjuna.ats.internal.arjuna.recovery.RecoveryManagerStatus;
import com.hp.mwtests.ts.arjuna.resources.BasicRecord;
import com.hp.mwtests.ts.arjuna.resources.CrashRecord;
import com.hp.mwtests.ts.arjuna.resources.ShutdownRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.management.MBeanException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class RecoveryManagerUnitTest {

    private final ObjStoreBrowser objStoreBrowser = new ObjStoreBrowser();
    private final RecoveryManager rm = RecoveryManager.manager();
    private final Map<Uid, Collection<AbstractRecord>> atomicActionsToClean = new HashMap<>();

    private long _timeout;

    @Parameterized.Parameters
    public static Collection<Long> timeouts() {
        // Any timeout less than 4000L has a probability to fail due to testSuspendResumeWithErrorTxn
        // The reason is that the txn in testSuspendResumeWithErrorTxn gets recovered but this process
        // cannot happen in less than 3 seconds (because of the way PeriodicRecovery works).
        return Arrays.asList(4000L, 7000L, 10000L);
    }

    public RecoveryManagerUnitTest(long timeout) {
        _timeout = timeout;
    }

    @BeforeClass
    public static void beforeClass() {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setPeriodicRecoveryPeriod(1);
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryBackoffPeriod(1);

        // Register CrashRecord record type so that it is persisted in the object store correctly
        RecordTypeManager.manager().add(new RecordTypeMap() {
            public Class<? extends AbstractRecord> getRecordClass() {
                return CrashRecord.class;
            }

            public int getType() {
                return RecordType.USER_DEF_FIRST0;
            }
        });

        // Register BasicRecord record type so that it is persisted in the object store correctly
        RecordTypeManager.manager().add(new RecordTypeMap() {
            public Class<? extends AbstractRecord> getRecordClass() {
                return BasicRecord.class;
            }

            public int getType() {
                return RecordType.USER_DEF_FIRST0;
            }
        });

        // Register ShutdownRecord record type so that it is persisted in the object store correctly
        RecordTypeManager.manager().add(new RecordTypeMap() {
            public Class<? extends AbstractRecord> getRecordClass() {
                return ShutdownRecord.class;
            }

            public int getType() {
                return RecordType.USER_DEF_FIRST0;
            }
        });
    }

    @Before
    public void setUp() {

        objStoreBrowser.setType("com.arjuna.ats.arjuna.AtomicAction", "com.arjuna.ats.arjuna.tools.osb.mbean.ActionBean");
        objStoreBrowser.start();

        rm.addModule(new AtomicActionRecoveryModule());

        recoveryPropertyManager.getRecoveryEnvironmentBean().setTimeoutToWaitUntilNoTxns(_timeout);
        recoveryPropertyManager.getRecoveryEnvironmentBean().setBackoffWaitUntilNoTxns(_timeout/50);

        if (recoveryPropertyManager.getRecoveryEnvironmentBean().isWaitUntilNoTxns()) {
            recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(false);
        }
    }

    @After
    public void cleanUp() throws MBeanException {

        cleanObjectStore();

        rm.removeAllModules(true);
        rm.terminate(false);

        objStoreBrowser.stop();
    }

    @Test
    public void testSuspendResume() throws InterruptedException {

        runTest();
    }

    @Test
    public void testSuspendResumeWithCleanObjectStore() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        runTest();
    }

    @Test
    public void testSuspendResumeWithHeuristicTxn() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with HEURISTIC_HAZARD outcome
        txns.put(ActionStatus.H_HAZARD,
                List.of(new CrashRecord(), new CrashRecord(CrashRecord.CrashLocation.CrashInCommit, CrashRecord.CrashType.HeuristicHazard))
        );

        runTest((x, y) -> x >= y, null, false, txns);
    }

    @Test
    public void testSuspendResumeWithHeuristicTxnNegativeTimeout() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        // Negative Timeout => Suspension should last forever
        recoveryPropertyManager.getRecoveryEnvironmentBean().setTimeoutToWaitUntilNoTxns(0);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with HEURISTIC_HAZARD outcome
        txns.put(ActionStatus.H_HAZARD,
                List.of(new CrashRecord(), new CrashRecord(CrashRecord.CrashLocation.CrashInCommit, CrashRecord.CrashType.HeuristicHazard))
        );

        Thread test = new Thread(() -> {
            // This thread will call RecoveryManager.suspend() thus it will check if the object store is empty.
            // As setTimeoutToWaitUntilNoTxns = 0, the checking loop will never end. To end the infinite loop,
            // test.interrupt() will be called.
            try {
                runTest((x, y) -> x >= y, null, false, txns);
            } catch (InterruptedException e) {
                // Safe to ignore: we are only checking that the thread is no longer alive
            }
        });

        test.start();
        // Wait a random time
        Thread.sleep(_timeout + 1000);
        if (test.isAlive()) {
            test.interrupt();
            // success
        } else {
            fail("The thread used to run the test (and call RecoveryManager.suspend()) should have been alive.");
        }

        if (!test.isInterrupted()) {
            fail("The thread used to run the test (and call RecoveryManager.suspend()) should have been interrupted.");
        }
    }

    @Test
    public void testSuspendResumeWithHeuristicTxnSwitchingWaitUntilNoTxns() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with HEURISTIC_HAZARD outcome
        txns.put(ActionStatus.H_HAZARD,
                List.of(new CrashRecord(), new CrashRecord(CrashRecord.CrashLocation.CrashInCommit, CrashRecord.CrashType.HeuristicHazard))
        );

        // Switch WaitUntilNoTxns after _timeout/2
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(false);
            }
        }, _timeout / 2);

        runTest((x, y) -> x < y, null, false, txns);
    }

    @Test
    public void testSuspendResumeWithSolvedHeuristicTxn() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with HEURISTIC_HAZARD outcome
        txns.put(ActionStatus.H_HAZARD,
                List.of(new CrashRecord(), new CrashRecord(CrashRecord.CrashLocation.CrashInCommit, CrashRecord.CrashType.HeuristicHazard))
        );

        // Switch WaitUntilNoTxns after _timeout/2 (second)
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    cleanObjectStore();
                } catch (MBeanException e) {
                    throw new RuntimeException(e);
                }
            }
        }, _timeout / 2);

        runTest((x, y) -> x < y, null, false, txns);
    }

    @Test
    public void testSuspendResumeWithAbortedTxn() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with ABORTED outcome.
        txns.put(ActionStatus.ABORTED,
                List.of(new BasicRecord(), new ShutdownRecord(ShutdownRecord.FAIL_IN_PREPARE))
        );

        runTest((x, y) -> x < y, null, false, txns);
    }

    @Test
    public void testSuspendResumeWithErrorTxn() throws InterruptedException {

        recoveryPropertyManager.getRecoveryEnvironmentBean().setWaitUntilNoTxns(true);

        Map<Integer, Collection<AbstractRecord>> txns = new HashMap<>();
        // Initiate a transaction that will end with an error during commit.
        txns.put(ActionStatus.COMMITTED,
                List.of(new BasicRecord(), new ShutdownRecord(ShutdownRecord.FAIL_IN_COMMIT))
        );

        runTest((x, y) -> x < y, null, false, txns);
    }

    private void runTest() throws InterruptedException {
        runTest(null, null, false, new HashMap<>());
    }

    private void runTest(BiFunction<Long, Long, Boolean> booleanOperator, RecoveryScan callback, boolean async, Map<Integer, Collection<AbstractRecord>> txns) throws InterruptedException {

        for (Map.Entry<Integer, Collection<AbstractRecord>> entry : txns.entrySet()) {
            // Create transactions
            AtomicAction txn = new AtomicAction();
            txn.begin();
            // Add records to txn
            for (AbstractRecord record : entry.getValue()) {
                txn.add(record);
            }
            atomicActionsToClean.put(txn.get_uid(), entry.getValue());
            txn.commit();
        }

        rm.scan(callback);

        Instant start = Instant.now();
        // Call the RM's suspend method with the constraint that the Object Store should be empty before suspending
        RecoveryManagerStatus status = rm.suspend(async);
        Instant stop = Instant.now();
        if (Objects.nonNull(booleanOperator)) {
            long duration = Duration.between(start, stop).toMillis();
            assertTrue(booleanOperator.apply(duration, _timeout));
        }

        // Checking that the RecoveryManager is SUSPENDED
        assertEquals(RecoveryManagerStatus.State.SUSPENDED, status.getState());
        // Checking that the transactions left in the Object Store are among what we started with
        for(Uid uid : status.getUids()) {
            assertTrue(atomicActionsToClean.containsKey(uid));
        }

        rm.resume();

        assertFalse(rm.getModules().isEmpty());
    }

    private void cleanObjectStore() throws MBeanException {

        if (!atomicActionsToClean.isEmpty()) {
            // Fetch all txns from the Object Store
            objStoreBrowser.probe();
            for (Map.Entry<Uid, Collection<AbstractRecord>> entry : atomicActionsToClean.entrySet()) {

                UidWrapper uidWrapper = objStoreBrowser.findUid(entry.getKey());

                // If there is not any UidWrapper corresponding to the Uid in atomicActionsToClean,
                // then it means that the txn has been already cleaned/recovered
                if (Objects.isNull(uidWrapper)) {
                    continue;
                }

                OSEntryBean bean = uidWrapper.getMBean();
                ActionBean actionBean = (ActionBean) bean;
                // Remove txn from the Object Store
                actionBean.remove();
            }

            atomicActionsToClean.clear();
        }
    }
}