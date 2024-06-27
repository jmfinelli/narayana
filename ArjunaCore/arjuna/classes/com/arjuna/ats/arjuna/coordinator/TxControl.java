/*
   Copyright The Narayana Authors
   SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.arjuna.coordinator;

import java.nio.charset.StandardCharsets;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.recovery.TransactionStatusManager;

/**
 * Transaction configuration object. We have a separate object for this so that
 * other classes can enquire of (and use) this information.
 *
 * @author Mark Little (mark@arjuna.com)
 * @version $Id: TxControl.java 2342 2006-03-30 13:06:17Z $
 * @since JTS 2.2.
 */

public class TxControl {
    /*
     * Lock controlling whether the Transaction system is enabled or not.
     * There is no point in trying to optimise the access to TxControl.enable
     * with "volatile" as TxControl is used very rarely (e.g. when Arjuna is
     * started and stopped).
     *
     * Although shutdownLock is mainly used to guard TxControl.enable, it is
     * also used to guard the shutdown of TransactionStatusManager. I don't
     * think it is worth to use an extra lock for TransactionStatusManager,
     * but you're encouraged to prove me wrong :-)
     */
    private static final Object shutdownLock = new Object();
    private static TransactionStatusManager transactionStatusManager = null;

    public static final int NODE_NAME_SIZE = 28;
    public static final String DEFAULT_NODE_NAME = "Arjuna:";

    static final boolean maintainHeuristics = arjPropertyManager.getCoordinatorEnvironmentBean().isMaintainHeuristics();
    static final boolean asyncCommit = arjPropertyManager.getCoordinatorEnvironmentBean().isAsyncCommit();
    static final boolean asyncPrepare = arjPropertyManager.getCoordinatorEnvironmentBean().isAsyncPrepare();
    static final boolean asyncRollback = arjPropertyManager.getCoordinatorEnvironmentBean().isAsyncRollback();
    static final boolean asyncBeforeSynch = arjPropertyManager.getCoordinatorEnvironmentBean().isAsyncBeforeSynchronization();
    static final boolean asyncAfterSynch = arjPropertyManager.getCoordinatorEnvironmentBean().isAsyncAfterSynchronization();
    static final boolean onePhase = arjPropertyManager.getCoordinatorEnvironmentBean().isCommitOnePhase();
    static final boolean readonlyOptimisation = arjPropertyManager.getCoordinatorEnvironmentBean().isReadonlyOptimisation();
    static final boolean dynamic1PC = arjPropertyManager.getCoordinatorEnvironmentBean().getDynamic1PC();

    // flag which is true if transaction service is enabled and false if it is disabled
    static boolean enable = !arjPropertyManager.getCoordinatorEnvironmentBean().isStartDisabled();

    static String xaNodeName = arjPropertyManager.getCoreEnvironmentBean().getNodeIdentifier();
    static byte[] xaNodeNameBytes = (xaNodeName == null ? null : xaNodeName.getBytes(StandardCharsets.UTF_8));
    static int _defaultTimeout = arjPropertyManager.getCoordinatorEnvironmentBean().getDefaultTimeout();

    /**
     * flag which is true if enable and disable operations, respectively, start and stop the transaction status
     * manager and false if they do not perform a start and stop. this flag is true by default and can only be
     * set to false by setting property @see#com.arjuna.ats.arjuna.common.TRANSACTION_STATUS_MANAGER_ENABLE
     * to value "NO"
     */
    static final boolean _enableTSM = arjPropertyManager.getCoordinatorEnvironmentBean().isTransactionStatusManagerEnable();

    static final boolean beforeCompletionWhenRollbackOnly = arjPropertyManager.getCoordinatorEnvironmentBean().isBeforeCompletionWhenRollbackOnly();
    static Thread _shutdownHook = null;

    /**
     * Creates transaction status manager.
     */
    static {
        // TODO -- add this check to respect the environment setting for Environment.START_DISABLED?
        // TODO -- is this feature actually needed (it appears not to be used internally)
        // if (enable) {
        createTransactionStatusManager();
        // }
    }

    public static class Shutdown extends Thread {
        public void run() {
            // guard against simultaneous user-initiated shutdown
            // synchronize on a dedicated lock since TxControl.removeTransactionStatusManager()
            // is also synchronized on the same lock
            synchronized (shutdownLock) {
                // check that this hook is still active
                if (_shutdownHook == this && transactionStatusManager != null) {
                    transactionStatusManager.shutdown();
                    transactionStatusManager = null;
                }
            }
        }
    }

    /**
     * If a timeout is not associated with a transaction when it is created then
     * this value will be used. A value of 0 means that the transaction will
     * never time out.
     */
    public static final int getDefaultTimeout() {
        return _defaultTimeout;
    }

    /**
     * Set the timeout to be associated with a newly created transaction if there is no
     * other timeout to be used.
     *
     * @param timeout
     */
    public static void setDefaultTimeout(int timeout) {
        _defaultTimeout = timeout;
    }

    /**
     * Start the transaction system. This allows new transactions to be created
     * and for recovery to execute.
     */

    public static void enable() {
        synchronized (shutdownLock) {
            createTransactionStatusManager();

            TxControl.enable = true;
        }
    }

    /**
     * Stop the transaction system. New transactions will be prevented but
     * recovery will be allowed to continue.
     */

    public static void disable() {
        synchronized (shutdownLock) {
            disable(false);
        }
    }

    /**
     * Stop the transaction system. New transactions will be prevented and
     * recovery will cease.
     * <p>
     * WARNING: make sure you know what you are doing when you call this
     * routine!
     */

    public static void disable(boolean disableRecovery) {
        synchronized (shutdownLock) {
            /*
             * We could have an implementation that did not return until all
             * transactions had finished. However, this could take an arbitrary
             * time, especially if participants could fail. Since this information
             * is available anyway to the application, let it handle it.
             */

            if (disableRecovery)
                removeTransactionStatusManager();

            TxControl.enable = false;

            /*
             * Signal to TxControl.waitUntilIsDisabled() that the Transaction system
             * has been disabled. There is a chance that this notification gets fired
             * while there isn't any thread waiting for it. However, as
             * waitUntilIsDisabled() checks TxControl.enable through a synchronized
             * block on shutdownLock, there is no chance for an infinite waiting.
             */
            shutdownLock.notify();
        }
    }


    public static boolean isEnabled() {
        synchronized (shutdownLock) {
            return TxControl.enable;
        }
    }

    /**
     * This method waits (synchronously) until the Transaction system
     * has been disabled. Note that this method will block the caller
     * thread.
     */
    public static void waitUntilIsDisabled() {
        synchronized (shutdownLock) {
            while (isEnabled()) {
                try {
                    if (tsLogger.logger.isDebugEnabled()) {
                        tsLogger.logger.debug("TxControl: waitUntilIsDisabled() is waiting for the TxControl.disable()");
                    }
                    shutdownLock.wait();
                } catch (InterruptedException ignore) {
                    // We can ignore this exception
                }
            }
        }
    }

    /**
     * @return the <code>ObjectStore</code> implementation which the
     * transaction coordinator will use.
     * @see com.arjuna.ats.arjuna.objectstore.ObjectStore
     */

    public static boolean getAsyncPrepare() {
        return asyncPrepare;
    }

    public static boolean getMaintainHeuristics() {
        return maintainHeuristics;
    }

    public static boolean isReadonlyOptimisation() {
        return readonlyOptimisation;
    }

    public static String getXANodeName() {
        return xaNodeName;
    }

    public static byte[] getXaNodeNameBytes() {
        return xaNodeNameBytes;
    }

    public static void setXANodeName(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > NODE_NAME_SIZE) {
            tsLogger.i18NLogger.warn_coordinator_toolong(NODE_NAME_SIZE);

            throw new IllegalArgumentException();
        }

        xaNodeName = name;
        xaNodeNameBytes = bytes;
    }


    public static boolean isBeforeCompletionWhenRollbackOnly() {
        return beforeCompletionWhenRollbackOnly;
    }

    private static void createTransactionStatusManager() {
        synchronized (shutdownLock) {
            if (transactionStatusManager == null && _enableTSM) {
                transactionStatusManager = new TransactionStatusManager();

                _shutdownHook = new Shutdown();

                // add hook to ensure finalize gets called.
                Runtime.getRuntime().addShutdownHook(_shutdownHook);
            }
        }
    }

    private static void removeTransactionStatusManager() {
        synchronized (shutdownLock) {
            if (_shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);

                _shutdownHook = null;

                if (transactionStatusManager != null) {
                    transactionStatusManager.shutdown();
                    transactionStatusManager = null;
                }
            }
        }
    }

}