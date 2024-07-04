package com.hp.mwtests.ts.arjuna.recovery;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.RecordType;
import com.arjuna.ats.arjuna.coordinator.TransactionReaper;
import com.arjuna.ats.arjuna.coordinator.TxControl;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeManager;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeMap;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;

import com.hp.mwtests.ts.arjuna.resources.BasicRecord;
import com.hp.mwtests.ts.arjuna.resources.BytemanControlledRecord;
import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.WithByteman;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

@WithByteman
@BMUnitConfig(debug=true)
@BMScript("recoverySuspend")
public class RecoverySuspendTest {

    private static RecoveryManager _manager;
    private static RecordTypeMap _recordTypeMap;

    // fields read with byteman
    private final static int periodicRecoveryPeriod = 5;
    private final static int recoveryBackoffPeriod = 1;
    private final static int numberOfFailures = 3;

    @BeforeAll
    public static void beforeClass() {

        RecoveryEnvironmentBean recoveryConfig = recoveryPropertyManager.getRecoveryEnvironmentBean();
        String[] modules = {
                // the module to test
                "com.arjuna.ats.internal.arjuna.recovery.AtomicActionRecoveryModule"
        };

        // Let's go quick on this
        recoveryConfig.setRecoveryBackoffPeriod(recoveryBackoffPeriod);
        recoveryConfig.setPeriodicRecoveryPeriod(periodicRecoveryPeriod);
        // don't sign off until the store is empty
        recoveryConfig.setWaitForFinalRecovery(true);

        // the test set of modules
        recoveryConfig.setRecoveryModuleClassNames(Arrays.asList(modules));

        // obtain a new RecoveryManager with the above config:
        _manager = RecoveryManager.manager(RecoveryManager.INDIRECT_MANAGEMENT);
        // recovery can start
        _manager.startRecoveryManagerThread();

        _recordTypeMap = new RecordTypeMap() {
            @SuppressWarnings("unchecked")
            public Class getRecordClass ()
            {
                return BytemanControlledRecord.class;
            }

            public int getType ()
            {
                return RecordType.USER_DEF_FIRST0;
            }
        };

        RecordTypeManager.manager().add(_recordTypeMap);
    }

    @BeforeEach
    public void beforeEach() {
        // In case the Recovery Manager was suspended, this will resume it
        _manager.resume();
    }

    @AfterAll
    public static void afterClass() {
        RecordTypeManager.manager().remove(_recordTypeMap);
    }

    @AfterEach
    public void afterEach() {
        if (!TxControl.isEnabled()) {
            // Re-activate transaction creation
            TxControl.enable();
        }
    }

    @Test
    public void testSuspensionWheneThereArentTxnsToRecover() {

        // The Transaction System needs to be disabled, i.e. no new transactions can be created
        TxControl.disable();

        // Makes sure that the transaction reaper completed all transactions
        TransactionReaper.transactionReaper().waitForAllTxnsToTerminate();

        long startSuspension = System.currentTimeMillis();

        // Synchronization is not needed (i.e. async = true) as isWaitForFinalRecovery() == true
        _manager.suspend(true);
        long durationOfSuspension = System.currentTimeMillis() - startSuspension;

        Assertions.assertTrue(durationOfSuspension <
                // In milliseconds
                1000L * (periodicRecoveryPeriod + recoveryBackoffPeriod), String.valueOf(durationOfSuspension));
    }

    @Test
    public void testSuspensionWhenThereIsAtomicActionToRecover() {

        // Set up how many attempts should fail
        BytemanControlledRecord.setCommitFailureCounter(numberOfFailures);

        createBasicAtomicAction();

        // The Transaction System needs to be disabled, i.e. no new transactions can be created
        TxControl.disable();

        // Makes sure that the transaction reaper completed all transactions
        TransactionReaper.transactionReaper().waitForAllTxnsToTerminate();

        // Synchronization is not needed (i.e. async = true) as isWaitForFinalRecovery() == true
        _manager.suspend(true);

        // BytemanControlledRecord.getCommitCallCounter() should be numberOfFailures + 1 as:
        // - one invocation from the normal commit procedure should fail
        // - (numberOfFailures - 1) invocations from the recovery process should fail
        // - one invocation from the recovery process should pass
        Assertions.assertTrue(BytemanControlledRecord.getCommitCallCounter() == (numberOfFailures + 1),
                String.format("BytemanControlledRecord's getCommitCallCounter is %d but it should have been %d",
                        BytemanControlledRecord.getCommitCallCounter(), (numberOfFailures + 1)));
    }

    private void createBasicAtomicAction() {
        // Reset the Recovery Counter through byteman
        BytemanControlledRecord.resetAll();

        AtomicAction A = new AtomicAction();

        BasicRecord basicRecordOne = new BasicRecord();
        BytemanControlledRecord basicRecordTwo = new BytemanControlledRecord(true);

        A.begin();

        A.add(basicRecordOne);
        A.add(basicRecordTwo);

        // should generate a recovery record
        A.commit();
    }
}
