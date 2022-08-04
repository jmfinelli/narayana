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
/*
 * Copyright (C) 1999-2001 by HP Bluestone Software, Inc. All rights Reserved.
 *
 * HP Arjuna Labs,
 * Newcastle upon Tyne,
 * Tyne and Wear,
 * UK.
 *
 * $Id: RecoveryManagerImple.java 2342 2006-03-30 13:06:17Z  $
 */

package com.arjuna.ats.internal.arjuna.recovery;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.TxControl;
import com.arjuna.ats.arjuna.exceptions.FatalError;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.objectstore.ObjectStoreIterator;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.recovery.RecoveryModule;

/**
 * The RecoveryManagerImple - does the real work. Currently we can have only one
 * of these per node, so each instance checks it's the only one running. If it
 * isn't it will kill itself before doing any work.
 */

public class RecoveryManagerImple
{
    private final PeriodicRecovery _periodicRecovery;
    private final RecActivatorLoader _recActivatorLoader;

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    /**
     * Does the work of setting up crash recovery.
     *
     * @param threaded
     *            if <code>true</code> then the manager will start a separate
     *            thread to run recovery periodically.
     */

    public RecoveryManagerImple (boolean threaded)
    {
        // by default we do not use a socket based listener,
	// but it can be turned on if not required.
        boolean useListener = recoveryPropertyManager.getRecoveryEnvironmentBean().isRecoveryListener();

        /*
        * Check whether there is a recovery daemon running - only allow one per
        * object store
        *
        * Note: this does not actually check if a recovery manager is running for the same ObjectStore,
        * only if one is on the same port as our configuration. Thus it's not particularly robust.
        * TODO: add a lock file to the ObjectStore as a belt and braces approach?
        *
        * This check works by trying to bind the server socket, so don't do it if we are running local only
        * (yup, that means there is a greater chance of winding up with more than one recovery manager if
        * we are running without a listener. See comment on robustness and file locking.)
        */

        if (useListener && isRecoveryManagerEndPointInUse())
        {

            try
            {
                tsLogger.i18NLogger.fatal_recovery_fail(RecoveryManager.getRecoveryManagerHost().getHostAddress(),
                        Integer.toString(RecoveryManager.getRecoveryManagerPort()));
            }
            catch (Throwable t)
            {
                tsLogger.i18NLogger.fatal_recovery_fail("unknown", "unknown");
            }


            throw new FatalError("Recovery manager already active (or recovery port and address are in use)!");
        }

        // start the activator recovery loader

        _recActivatorLoader = new RecActivatorLoader();
        _recActivatorLoader.startRecoveryActivators();

        // start the periodic recovery thread
        // (don't start this until just about to go on to the other stuff)

        _periodicRecovery = new PeriodicRecovery(threaded, useListener);

        /*
         * Start the expiry scanner
         *
         * This has to happen after initiating periodic recovery, because periodic recovery registers record types used
         * by the expiry scanner
         */
        ExpiredEntryMonitor.startUp();

        try
        {
            if (tsLogger.logger.isInfoEnabled())
            {
                if(useListener)
                {
                    tsLogger.i18NLogger.info_recovery_socketready( Integer.toString(_periodicRecovery.getServerSocket().getLocalPort()));
                }
                else
                {
                    tsLogger.logger.debug("RecoveryManagerImple is ready. Socket listener is turned off.");
                }
            }
        }
        catch (IOException ex) {
            tsLogger.i18NLogger.warn_recovery_RecoveryManagerImple_2(ex);
        }
    }

    public final void scan ()
    {
        _periodicRecovery.doWork();
    }

    public final void addModule (RecoveryModule module)
    {
        _periodicRecovery.addModule(module);
    }

    public final void removeModule (RecoveryModule module, boolean waitOnScan)
    {
        _periodicRecovery.removeModule(module, waitOnScan);
    }

    public final void removeAllModules (boolean waitOnScan)
    {
        _periodicRecovery.removeAllModules(waitOnScan);
    }

    public final Vector<RecoveryModule> getModules ()
    {
        return _periodicRecovery.getModules();
    }

    public void start ()
    {
        if (!_periodicRecovery.isAlive())
        {
            _periodicRecovery.start();
        }
    }

    /**
     * stop the recovery manager
     * @param async false means wait for any recovery scan in progress to complete
     */
    public void stop (boolean async)
    {
        // must ensure we clean up dependent threads

        ExpiredEntryMonitor.shutdown();

        _periodicRecovery.shutdown(async);
    }

    /**
     * Suspend the Recovery Manager. This method changes behaviour based on {@link RecoveryEnvironmentBean#setWaitUntilNoTxns(boolean)}.
     * When this parameter is set to true, the Recovery Manager gets suspended ONLY after the Object Store is verified to
     * be empty. In case the {@link RecoveryEnvironmentBean#setWaitUntilNoTxns(boolean)} is set to false, the Recovery Manager
     * gets suspended without checking if there are transactions left in the Object Store. In both cases, if the Recovery Manager
     * is running a recovery scan, it will be suspended only afterwards, in order to preserve data integrity.
     * <b>This method must be used with {@code synchronized}.</b>
     *
     * @param async false means wait for the recovery manager to finish any scans before returning.
     * @return {@link RecoveryManagerStatus} to inform how the suspension finished
     * @throws InterruptedException when the calling thread is interrupted; the calling thread is responsible for checking
     * whether the Object Store is empty thus any interruption happening during this task should be communicated to the caller.
     * @throws ObjectStoreException when there are issues with {@link ObjectStoreIterator}
     * @throws IOException when there are issues with {@link ObjectStoreIterator}
     */
    public RecoveryManagerStatus trySuspendScan (boolean async) throws InterruptedException, ObjectStoreException, IOException
    {
        if (recoveryPropertyManager.getRecoveryEnvironmentBean().isWaitUntilNoTxns())
        {
            // Stop the transaction system. New transactions will not be created
            TxControl.disable();

            // Check if there are transactions in the Object Store
            Instant start = Instant.now();
            Instant check = start;

            while (!isObjectStoreEmpty(null) &&
                    recoveryPropertyManager.getRecoveryEnvironmentBean().isWaitUntilNoTxns() &&
                    (recoveryPropertyManager.getRecoveryEnvironmentBean().getTimeoutToWaitUntilNoTxns() <= 0 ||
                            Duration.between(start, check).toMillis() < recoveryPropertyManager.getRecoveryEnvironmentBean().getTimeoutToWaitUntilNoTxns()))
            {
                TimeUnit.MILLISECONDS.sleep(recoveryPropertyManager.getRecoveryEnvironmentBean().getBackoffWaitUntilNoTxns());
                check = Instant.now();
            }
        }

        // If the Object Store is not empty at this point (and the Recovery Manager was configured to suspend only when there were no
        // transactions left in the Object Store), suspending the periodic recovery after an ongoing scan will not help to recover
        // in-doubt transactions. As a consequence, async is overridden by isWaitUntilNoTxns. If WaitUntilNoTxns was set to false,
        // then async can decide if suspendScan will wait for the completion of the ongoing scan
        _periodicRecovery.suspendScan(!recoveryPropertyManager.getRecoveryEnvironmentBean().isWaitUntilNoTxns() && async);
        // Populates uidsLeftInTheObjectStore with transactions left in the Object Store (in case there are any)
        Collection<Uid> uidsLeftInTheObjectStore = new ArrayList<>();
        isObjectStoreEmpty(uidsLeftInTheObjectStore);
        return new RecoveryManagerStatus(_periodicRecovery.getMode(), uidsLeftInTheObjectStore);
    }

    private boolean isObjectStoreEmpty(Collection<Uid> txnsLeftInTheObjectStore) throws ObjectStoreException, IOException
    {
        boolean empty = true;
        // Holds the list of types
        final List<String> typeOfInterest = new ArrayList<>();

        this.getModules().forEach(recoveryModule -> typeOfInterest.addAll(recoveryModule.getTypes()));

        for (String type : typeOfInterest) {
            if (Objects.nonNull(type)) {
                ObjectStoreTypeChecker objectStoreTypeChecker = new ObjectStoreTypeChecker(StoreManager.getRecoveryStore(), type);
                empty &= objectStoreTypeChecker.areThereTxns();
                if (Objects.nonNull(txnsLeftInTheObjectStore)) {
                    // Loads Uids in the external buffer. NB Uid is immutable
                    txnsLeftInTheObjectStore.addAll(objectStoreTypeChecker.getTxns());
                }
            }
        }

        return empty;
    }

    public void resumeScan ()
    {
        TxControl.enable();
        _periodicRecovery.resumeScan();
    }

    /**
     * wait for the recovery implementation to be shut down.
     */
    public void waitForTermination ()
    {
        try
        {
            _periodicRecovery.join();
        }
        catch (final Exception ex)
        {
        }
    }

    /**
     * Test whether the recovery manager (RM) port and address are available - if not assume that another
     * recovery manager is already active.
     *
     * Ideally this method needs to discover whether or not another RM is already monitoring the object store
     *
     * @return true if the RM port and address are in use
     */
    private final boolean isRecoveryManagerEndPointInUse ()
    {
        /*
        * attempt to create the server socket. If an exception is thrown then some other
        * process is using the RM endpoint
        */
        if(_periodicRecovery != null)
        {
            return _periodicRecovery.getMode() != PeriodicRecovery.Mode.TERMINATED;
        } else
        {
            return false;
        }
    }

    /**
     * <p>
     *     This class checks if there are transactions of a specified type in the Object Store.
     * </p>
     * <p>
     *     This class has been designed as a helper class for {@link RecoveryManagerImple} thus more
     *     considerations should be given in case its visibility needs to be changed.
     * </p>
     */
    private static class ObjectStoreTypeChecker
    {
        private final ObjectStoreIterator objectStoreIterator;
        private final List<Uid> txns;

        private ObjectStoreTypeChecker(RecoveryStore recoveryStore, String type) throws ObjectStoreException
        {
            this.objectStoreIterator = new ObjectStoreIterator(recoveryStore, type);
            txns = new ArrayList<>();
        }

        /**
         * Checks if there are transactions of a specified type in the Object Store
         * @return true if there are transactions, false otherwise
         * @throws IOException in case {@link ObjectStoreIterator} throws an {@link IOException}
         */
        private boolean areThereTxns() throws IOException
        {
            Uid uid;
            txns.clear();

            while (!Uid.nullUid().equals(uid = objectStoreIterator.iterate()))
            {
                txns.add(uid);
            }

            return txns.isEmpty();
        }

        /**
         * This method should be called ONLY after {@link ObjectStoreTypeChecker#areThereTxns()}, otherwise
         * {@link ObjectStoreTypeChecker#txns} would be empty. This is not a problem as this class is private and only
         * used inside {@link RecoveryManagerImple}. Be careful though! If there is the need to change the visibility
         * of this class to public, further consideration should be given to how {@link ObjectStoreTypeChecker}'s methods
         * interact with each other. For example, synchronization should be used to check if {@link ObjectStoreTypeChecker#txns}
         * is empty or not before returning Uids from the Object Store.
         * @return {@link List} of {@link Uid} wrapped with {@link Collections#unmodifiableList}
         */
        private List<Uid> getTxns() {
             // It does not really matter that txns is published as:
             // - Collections.unmodifiableList stops possible modifications of the Collection
             // - The Uid class is immutable
            return Collections.unmodifiableList(txns);
        }
    }
}
