/*
   Copyright The Narayana Authors
   SPDX short identifier: Apache-2.0
 */

package com.hp.mwtests.ts.arjuna.resources;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.coordinator.AbstractRecord;
import com.arjuna.ats.arjuna.coordinator.RecordType;
import com.arjuna.ats.arjuna.coordinator.TwoPhaseOutcome;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;

public class BytemanControlledCrashRecord extends AbstractRecord {

    // It can be read from byteman using:
    // BytemanControlledCrashRecord._failCounter
    private static int _failCounter;

    // This needs to be set before running the test that uses this record type
    public static void setFailCounter(int failCounter) {
        BytemanControlledCrashRecord._failCounter = failCounter;
    }

    public static void resetCounter() {
        // The magic happens in the byteman script
    }

    public static int getFailCounter() {
        return BytemanControlledCrashRecord._failCounter;
    }

    public BytemanControlledCrashRecord() {
    }

    public BytemanControlledCrashRecord(boolean newRecord) {
        super(new Uid());
    }

    public int typeIs() {
        return RecordType.USER_DEF_FIRST0;
    }

    public boolean doSave() {
        return true;
    }

    public String type() {
        return "/StateManager/AbstractRecord/BytemanControlledCrashRecord";
    }

    public boolean save_state(OutputObjectState os, int ot) {
        return super.save_state(os, ot);
    }

    public boolean restore_state(InputObjectState os, int ot) {
        return super.restore_state(os, ot);
    }

    public Object value() {
        return null;
    }

    public void setValue(Object object) {
    }

    public int nestedAbort() {
        return TwoPhaseOutcome.FINISH_OK;
    }

    public int nestedCommit() {
        return TwoPhaseOutcome.FINISH_ERROR;
    }

    public int nestedPrepare() {
        return TwoPhaseOutcome.PREPARE_NOTOK;
    }

    public int topLevelAbort() {
        return TwoPhaseOutcome.FINISH_OK;
    }

    public int topLevelCommit() {
        return TwoPhaseOutcome.FINISH_OK;
    }

    public int topLevelPrepare() {
        return TwoPhaseOutcome.PREPARE_OK;
    }

    public void alter(AbstractRecord abstractRecord) {
    }

    public void merge(AbstractRecord abstractRecord) {
    }

    public boolean shouldAdd(AbstractRecord abstractRecord) {
        return false;
    }

    public boolean shouldAlter(AbstractRecord abstractRecord) {
        return false;
    }

    public boolean shouldMerge(AbstractRecord abstractRecord) {
        return false;
    }

    public boolean shouldReplace(AbstractRecord abstractRecord) {
        return false;
    }

}