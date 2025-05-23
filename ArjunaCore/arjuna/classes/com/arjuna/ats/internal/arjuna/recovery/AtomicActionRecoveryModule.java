/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package com.arjuna.ats.internal.arjuna.recovery;

import java.util.Enumeration;
import java.util.Objects;
import java.util.Vector;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.ActionStatus;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoverAtomicAction;
import com.arjuna.ats.arjuna.recovery.RecoveryModule;
import com.arjuna.ats.arjuna.recovery.TransactionStatusConnectionManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;

/**
 * This class is a plug-in module for the recovery manager.
 * It is responsible for recovering failed AtomicAction transactions.
 */
public class AtomicActionRecoveryModule implements RecoveryModule {
    public AtomicActionRecoveryModule() {
        if (tsLogger.logger.isDebugEnabled()) {
            tsLogger.logger.debug("AtomicActionRecoveryModule created");
        }

        if (_recoveryStore == null) {
            _recoveryStore = StoreManager.getRecoveryStore();
        }

        _transactionStatusConnectionMgr = new TransactionStatusConnectionManager();
    }

    /**
     * This is called periodically by the RecoveryManager
     */
    public void periodicWorkFirstPass() {
        // Transaction type
        boolean AtomicActions = false;
        // Does not block the suspension of the Recovery Manager by default
        this.hasWorkLeftToDo = false;
        // Does not report problems
        this.problemDuringRecovery = false;

        // uids per transaction type
        InputObjectState aa_uids = new InputObjectState();

        try {
            if (tsLogger.logger.isDebugEnabled()) {
                tsLogger.logger.debug("AtomicActionRecoveryModule first pass");
            }

            AtomicActions = _recoveryStore.allObjUids(_transactionType, aa_uids);

        } catch (ObjectStoreException ex) {
            problemDuringRecovery = true;
            tsLogger.i18NLogger.warn_recovery_AtomicActionRecoveryModule_1(ex);
        }

        if (AtomicActions) {
            _transactionUidVector = processTransactions(aa_uids);
        }
    }

    public void periodicWorkSecondPass() {
        if (tsLogger.logger.isDebugEnabled()) {
            tsLogger.logger.debug("AtomicActionRecoveryModule second pass");
        }

        processTransactionsStatus();
    }

    protected AtomicActionRecoveryModule(String type) {
        if (tsLogger.logger.isDebugEnabled()) {
            tsLogger.logger.debug("AtomicActionRecoveryModule created");
        }

        if (_recoveryStore == null) {
            _recoveryStore = StoreManager.getRecoveryStore();
        }

        _transactionStatusConnectionMgr = new TransactionStatusConnectionManager();
        _transactionType = type;

    }

    private RecoverAtomicAction doRecoverTransaction(Uid recoverUid) {
        boolean commitThisTransaction = true;

        /*
         * Retrieve the transaction status from its original process.
         * Note: this can be the status of the transaction from the object store
         */
        int theStatus = _transactionStatusConnectionMgr.getTransactionStatus(_transactionType, recoverUid);

        boolean inFlight = isTransactionInMidFlight(theStatus);

        String Status = ActionStatus.stringForm(theStatus);

        if (tsLogger.logger.isDebugEnabled()) {
            tsLogger.logger.debug("transaction type is " + _transactionType + " uid is " +
                    recoverUid.toString() + "\n ActionStatus is " + Status +
                    " in flight is " + inFlight);
        }

        RecoverAtomicAction rcvAtomicAction = null;
        if (!inFlight) {
            try {
                rcvAtomicAction = new RecoverAtomicAction(recoverUid, theStatus);

                rcvAtomicAction.replayPhase2();
            } catch (Exception ex) {
                problemDuringRecovery = true;
                tsLogger.i18NLogger.warn_recovery_AtomicActionRecoveryModule_2(recoverUid, ex);
            }
        }

        return rcvAtomicAction;
    }

    private boolean isTransactionInMidFlight(int status) {
        boolean inFlight = false;

        switch (status) {
            // these states can only come from a process that is still alive
            case ActionStatus.RUNNING:
            case ActionStatus.ABORT_ONLY:
            case ActionStatus.PREPARING:
            case ActionStatus.COMMITTING:
            case ActionStatus.ABORTING:
            case ActionStatus.PREPARED:
                inFlight = true;
                break;

            /*
             * the transaction is apparently still there, but has completed its
             * phase2. should be safe to redo it.
             */
            case ActionStatus.COMMITTED:
            case ActionStatus.H_COMMIT:
            case ActionStatus.H_MIXED:
            case ActionStatus.H_HAZARD:
            case ActionStatus.ABORTED:
            case ActionStatus.H_ROLLBACK:
                inFlight = false;
                break;

            // this shouldn't happen
            case ActionStatus.INVALID:
            default:
                inFlight = false;
        }

        return inFlight;
    }

    private Vector processTransactions(InputObjectState uids) {
        Vector uidVector = new Vector();

        if (tsLogger.logger.isDebugEnabled()) {
            tsLogger.logger.debug("processing " + _transactionType
                    + " transactions");
        }

        Uid theUid = null;

        boolean moreUids = true;

        while (moreUids) {
            try {
                theUid = UidHelper.unpackFrom(uids);

                if (theUid.equals(Uid.nullUid())) {
                    moreUids = false;
                } else {
                    Uid newUid = new Uid(theUid);

                    if (tsLogger.logger.isDebugEnabled()) {
                        tsLogger.logger.debug("found transaction " + newUid);
                    }

                    uidVector.addElement(newUid);
                }
            } catch (Exception ex) {
                moreUids = false;
            }
        }
        return uidVector;
    }

    private void processTransactionsStatus() {
        /*
         * JBTM-2016 If the volatile object store is used we would not be able
         * to recover anything but if this module is still configured it would
         * get an NPE
         */
        if (_transactionUidVector != null) {
            // Process the Vector of transaction Uids
            Enumeration transactionUidEnum = _transactionUidVector.elements();

            while (transactionUidEnum.hasMoreElements()) {
                Uid currentUid = (Uid) transactionUidEnum.nextElement();

                try {
                    if (_recoveryStore.currentState(currentUid,
                            _transactionType) != StateStatus.OS_UNKNOWN) {
                        RecoverAtomicAction rcvAtomicAction = doRecoverTransaction(currentUid);

                        if (Objects.nonNull(rcvAtomicAction)) {
                            /*
                             * hasFailedParticipants() relies on reportHeuristics being set to true
                             * during replay_completion in RecoverAtomicAction
                             */
                            if (rcvAtomicAction.hasFailedParticipants() ||
                                    rcvAtomicAction.hasPreparedParticipants() ||
                                    (recoveryPropertyManager.getRecoveryEnvironmentBean().isWaitForHeuristicDuringSuspension() &&
                                            rcvAtomicAction.hasHeuristicParticipants())) {
                                this.hasWorkLeftToDo = true;
                            } else if (rcvAtomicAction.hasHeuristicParticipants()) {
                                tsLogger.logger.tracef(
                                        "AtomicActionRecoveryModule.processTransactionsStatus heuristic action {0} " +
                                                "was ignored during the assessment of leftover work.", currentUid);
                            }
                        }
                    }
                } catch (ObjectStoreException ex) {
                    tsLogger.i18NLogger
                            .warn_recovery_AtomicActionRecoveryModule_3(
                                    currentUid, ex);

                    // There might still be work to do if currentState throws an ObjectStoreException
                    this.problemDuringRecovery = true;
                }
            }
        }
    }

    @Override
    public boolean hasWorkLeftToDo() {
        return this.hasWorkLeftToDo || this.problemDuringRecovery;
    }

    // 'type' within the Object Store for AtomicActions.
    private String _transactionType = new AtomicAction().type();

    // Array of transactions found in the object store of the AtomicAction type.
    private Vector _transactionUidVector = null;

    // Reference to the Object Store.
    private static RecoveryStore _recoveryStore = null;

    // This object manages the interface to all TransactionStatusManagers processes(JVMs) on this system/node.
    private TransactionStatusConnectionManager _transactionStatusConnectionMgr;

    private boolean hasWorkLeftToDo;

    private boolean problemDuringRecovery;

}
