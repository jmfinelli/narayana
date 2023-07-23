package com.hp.mwtests.ts.arjuna.recovery;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.RecordType;
import com.arjuna.ats.arjuna.coordinator.TxControl;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeManager;
import com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeMap;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;

import com.hp.mwtests.ts.arjuna.resources.BytemanControlledCrashRecord;
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
@BMUnitConfig(bmunitVerbose=true, debug=true)
@BMScript("recoverySuspend")
public class RecoverySuspendTest {

    private static RecoveryManager _manager;
    private static RecordTypeMap _recordTypeMap;

    // fields read with byteman
    private final static int periodicRecoveryPeriod = 5;
    private final static int recoveryBackoffPeriod = 1;

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

        // the test set of modules
        recoveryConfig.setRecoveryModuleClassNames(Arrays.asList(modules));

        // obtain a new RecoveryManager with the above config:
        _manager = RecoveryManager.manager(RecoveryManager.INDIRECT_MANAGEMENT);
        // don't sign off until the store is empty
        _manager.setWaitForFinalRecovery(true);
        // recovery can start
        _manager.startRecoveryManagerThread();

        _recordTypeMap = new RecordTypeMap() {
            @SuppressWarnings("unchecked")
            public Class getRecordClass ()
            {
                return BytemanControlledCrashRecord.class;
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

        long start = System.currentTimeMillis();
        // Needs to disable the creation of new transactions
        TxControl.disable();
        _manager.suspend(true);
        long duration = System.currentTimeMillis() - start;

        Assertions.assertTrue(duration <
                // In milliseconds
                1000L * (periodicRecoveryPeriod + recoveryBackoffPeriod), String.valueOf(duration));
    }

    @Test
    public void testSuspensionWhenThereIsAtomicActionToRecover() {

        // Set up how many attempts should fail
        BytemanControlledCrashRecord.setFailCounter(3);

        createBasicAtomicAction();

        long start = System.currentTimeMillis();
        // Needs to disable the creation of new transactions
        TxControl.disable();
        _manager.suspend(true);
        long duration = System.currentTimeMillis() - start;

        Assertions.assertTrue(duration >
                // In milliseconds
                1000L * (periodicRecoveryPeriod + recoveryBackoffPeriod) * BytemanControlledCrashRecord.getFailCounter(),
                String.valueOf(duration));
    }

    private void createBasicAtomicAction() {
        // Reset the Recovery Counter through byteman
        BytemanControlledCrashRecord.resetCounter();

        AtomicAction A = new AtomicAction();

        BytemanControlledCrashRecord basicRecordOne = new BytemanControlledCrashRecord(true);
        BytemanControlledCrashRecord basicRecordTwo = new BytemanControlledCrashRecord(true);

        A.begin();

        A.add(basicRecordOne);
        A.add(basicRecordTwo);

        // should generate a recovery record
        A.commit();
    }
}
