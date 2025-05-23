/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.arjuna.common;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.recovery.ExpiryScanner;
import com.arjuna.ats.arjuna.recovery.RecoveryActivator;
import com.arjuna.ats.arjuna.recovery.RecoveryModule;
import com.arjuna.ats.arjuna.utils.Utility;
import com.arjuna.ats.internal.arjuna.recovery.PeriodicRecovery;
import com.arjuna.common.internal.util.ClassloadingUtility;
import com.arjuna.common.internal.util.propertyservice.ConcatenationPrefix;
import com.arjuna.common.internal.util.propertyservice.FullPropertyName;
import com.arjuna.common.internal.util.propertyservice.PropertyPrefix;

/**
 * A JavaBean containing configuration properties for the recovery system.
 *
 * @author Jonathan Halliday (jonathan.halliday@redhat.com)
 */
@PropertyPrefix(prefix = "com.arjuna.ats.arjuna.recovery.")
public class RecoveryEnvironmentBean implements RecoveryEnvironmentBeanMBean {
    private volatile int periodicRecoveryPeriod = PeriodicRecovery._defaultRecoveryPeriod;
    private volatile int recoveryBackoffPeriod = PeriodicRecovery._defaultBackoffPeriod;
    private volatile int periodicRecoveryInitilizationOffset = 0;
    private volatile boolean recoveryListener = false;
    private volatile int recoveryPort = 0;
    private volatile String recoveryAddress = "localhost";
    private volatile int transactionStatusManagerPort = 0;
    private volatile String transactionStatusManagerAddress = "localhost";

    private volatile int expiryScanInterval = 12; // hours
    private volatile int transactionStatusManagerExpiryTime = 12; // hours

    @Deprecated(forRemoval = true)
    private volatile boolean waitForWorkLeftToDo = false;
    private volatile boolean waitForHeuristicDuringSuspension = false;

    @ConcatenationPrefix(prefix = "com.arjuna.ats.arjuna.recovery.expiryScanner")
    private volatile List<String> expiryScannerClassNames = new ArrayList<String>();
    private volatile List<ExpiryScanner> expiryScanners = null;

    @ConcatenationPrefix(prefix = "com.arjuna.ats.arjuna.recovery.recoveryExtension")
    private volatile List<String> recoveryModuleClassNames = new ArrayList<String>();
    private volatile List<RecoveryModule> recoveryModules = null;

    @ConcatenationPrefix(prefix = "com.arjuna.ats.arjuna.recovery.recoveryActivator")
    private volatile List<String> recoveryActivatorClassNames = new ArrayList<String>();
    private volatile List<RecoveryActivator> recoveryActivators = null;

    @FullPropertyName(name = "com.arjuna.ats.internal.arjuna.recovery.listener.timeoutsocket")
    private volatile boolean timeoutSocket = false;

    /**
     * Returns the interval between recovery scans, in seconds.
     * <p>
     * Default: 120 seconds
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.periodicRecoveryPeriod
     *
     * @return the recovery scan period, in seconds.
     */
    public int getPeriodicRecoveryPeriod() {
        return periodicRecoveryPeriod;
    }

    /**
     * Sets the interval between recovery scans, in seconds.
     *
     * @param periodicRecoveryPeriod the recovery scan period, in seconds.
     */
    public void setPeriodicRecoveryPeriod(int periodicRecoveryPeriod) {
        this.periodicRecoveryPeriod = periodicRecoveryPeriod;

        if (tsLogger.logger.isDebugEnabled() && this.periodicRecoveryPeriod != PeriodicRecovery._defaultRecoveryPeriod) {
            tsLogger.logger.debug("com.arjuna.ats.arjuna.recovery.PeriodicRecovery" +
                    ": Recovery period was set to " + this.periodicRecoveryPeriod + " seconds");
        }
    }

    /**
     * Returns the interval between recovery phases within a recovery scan, in seconds.
     * <p>
     * Default: 10 seconds
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.recoveryBackoffPeriod
     *
     * @return the interval between recovery phases, in seconds.
     */
    public int getRecoveryBackoffPeriod() {
        return recoveryBackoffPeriod;
    }

    /**
     * Sets the interval between recovery phases, in seconds.
     *
     * @param recoveryBackoffPeriod the interval between recovery scan phases, in seconds.
     */
    public void setRecoveryBackoffPeriod(int recoveryBackoffPeriod) {
        this.recoveryBackoffPeriod = recoveryBackoffPeriod;

        if (tsLogger.logger.isDebugEnabled() && this.recoveryBackoffPeriod != PeriodicRecovery._defaultBackoffPeriod) {
            tsLogger.logger.debug("PeriodicRecovery" +
                    ": Backoff period was set to " + this.recoveryBackoffPeriod + " seconds");
        }
    }


    /**
     * Returns the initial period to wait before starting recovery. Useful when starting multiple servers at once.
     *
     * @return The initial offset (in seconds)
     */
    public int getPeriodicRecoveryInitilizationOffset() {
        return periodicRecoveryInitilizationOffset;
    }

    /**
     * Set the period of time to wait before starting recovery.
     *
     * @param periodicRecoveryInitilizationOffset The period (in seconds) to wait before starting first recovery run.
     */
    public RecoveryEnvironmentBean setPeriodicRecoveryInitilizationOffset(int periodicRecoveryInitilizationOffset) {
        this.periodicRecoveryInitilizationOffset = periodicRecoveryInitilizationOffset;
        return null;
    }

    /**
     * Returns true if the recovery system should listen on a network socket.
     * <p>
     * Default: true
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.recoveryListener
     *
     * @return true for network recovery, false for local JVM only use.
     */
    public boolean isRecoveryListener() {
        return recoveryListener;
    }

    /**
     * Sets if the recovery system should listen on a network socket or not.
     *
     * @param recoveryListener true for network use, false for local JVM only.
     */
    public void setRecoveryListener(boolean recoveryListener) {
        this.recoveryListener = recoveryListener;
    }

    /**
     * Returns the network port number on which the recovery listener should bind.
     * <p>
     * Default: 0
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.recoveryPort
     *
     * @return the port number for recovery listener.
     */
    public int getRecoveryPort() {
        return recoveryPort;
    }

    /**
     * Sets the network port number on which the recovery listener should bind.
     *
     * @param recoveryPort the port number for the recovery listener.
     */
    public void setRecoveryPort(int recoveryPort) {
        Utility.validatePortRange(recoveryPort);
        this.recoveryPort = recoveryPort;
    }

    /**
     * Returns the hostname on which the recovery listener should bind.
     * <p>
     * Default: "localhost"
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.recoveryAddress
     *
     * @return the hostname on which the recovery system will listen.
     */
    public String getRecoveryAddress() {
        return recoveryAddress;
    }

    /**
     * Sets the hostname on which the recovery listener should bind.
     *
     * @param recoveryAddress the hostname on which the recovery system will listen.
     */
    public void setRecoveryAddress(String recoveryAddress) {
        this.recoveryAddress = recoveryAddress;
    }

    /**
     * Sets the InetAddress on which the recovery listener should bind.
     * Mainly intended for use by strongly typed bean injection systems,
     * this is a wrapper around the String form of the method.
     *
     * @param inetAddress the address on which to bind the recovery listener.
     */
    public void setRecoveryInetAddress(InetAddress inetAddress) {
        setRecoveryAddress(inetAddress.getHostAddress());
    }

    /**
     * Returns the network port number on which the transaction status manager should bind.
     * <p>
     * Default: 0
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.transactionStatusManagerPort
     *
     * @return the port number for the transaction status manager listener.
     */
    public int getTransactionStatusManagerPort() {
        return transactionStatusManagerPort;
    }

    /**
     * Sets the network port number on which the transaction status manager should bind.
     *
     * @param transactionStatusManagerPort the port number for the transaction status manager listener.
     */
    public void setTransactionStatusManagerPort(int transactionStatusManagerPort) {
        Utility.validatePortRange(transactionStatusManagerPort);
        this.transactionStatusManagerPort = transactionStatusManagerPort;
    }

    /**
     * Returns the hostname on which the transaction status manager should bind.
     * <p>
     * Default: "localhost"
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.transactionStatusManagerAddress
     *
     * @return the hostname on which the transaction status manager will listen.
     */
    public String getTransactionStatusManagerAddress() {
        return transactionStatusManagerAddress;
    }

    /**
     * Sets the hostname on which the transaction status manager should bind.
     *
     * @param transactionStatusManagerAddress the hostname on which the transaction status manager will listen.
     */
    public void setTransactionStatusManagerAddress(String transactionStatusManagerAddress) {
        this.transactionStatusManagerAddress = transactionStatusManagerAddress;
    }

    /**
     * Sets the InetAddress on which the transaction status manager should bind.
     * Mainly intended for use by strongly typed bean injection systems,
     * this is a wrapper around the String form of the method.
     *
     * @param inetAddress the address on which to bind the transaction status manager.
     */
    public void setTransactionStatusManagerInetAddress(InetAddress inetAddress) {
        setTransactionStatusManagerAddress(inetAddress.getHostAddress());
    }

    /**
     * Returns the interval on which the ObjectStore will be scanned for expired items, in hours.
     * <p>
     * Default: 12 hours
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.expiryScanInterval
     *
     * @return the interval between ObjectStore expiry checks, in hours.
     */
    public int getExpiryScanInterval() {
        return expiryScanInterval;
    }

    /**
     * Sets the interval on which the ObjectStore will be scanned for expired items, in hours.
     *
     * @param expiryScanInterval the interval between ObjectStore expiry checks, in hours.
     */
    public void setExpiryScanInterval(int expiryScanInterval) {
        this.expiryScanInterval = expiryScanInterval;
    }

    /**
     * Returns the time period after which items will be considered to have expired, in hours.
     * <p>
     * Default: 12 hours
     * Equivalent deprecated property: com.arjuna.ats.arjuna.recovery.transactionStatusManagerExpiryTime
     *
     * @return the expiry timeout, in hours.
     */
    public int getTransactionStatusManagerExpiryTime() {
        return transactionStatusManagerExpiryTime;
    }

    /**
     * Sets the time period after which items will be considered to have expired, in hours.
     *
     * @param transactionStatusManagerExpiryTime the expiry timeout, in hours.
     */
    public void setTransactionStatusManagerExpiryTime(int transactionStatusManagerExpiryTime) {
        this.transactionStatusManagerExpiryTime = transactionStatusManagerExpiryTime;
    }

    /**
     * Returns a list of names of classes that implement ExpiryScanner.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * Default: empty list.
     * Equivalent deprecated property prefix: com.arjuna.ats.arjuna.recovery.expiryScanner
     *
     * @return a list of ExpiryScanner implementation class names.
     */
    public List<String> getExpiryScannerClassNames() {
        synchronized (this) {
            return new ArrayList<String>(expiryScannerClassNames);
        }
    }

    /**
     * Sets the expiry scanners.
     * List elements should be names of classes that implement ExpiryScanner.
     * The provided list will be copied, not retained.
     *
     * @param expiryScannerClassNames a list of ExpiryScanner implementation class names.
     */
    public void setExpiryScannerClassNames(List<String> expiryScannerClassNames) {
        synchronized (this) {
            if (expiryScannerClassNames == null) {
                this.expiryScanners = new ArrayList<ExpiryScanner>();
                this.expiryScannerClassNames = new ArrayList<String>();
            } else if (!expiryScannerClassNames.equals(this.expiryScannerClassNames)) {
                this.expiryScanners = null;
                this.expiryScannerClassNames = new ArrayList<String>(expiryScannerClassNames);
            }
        }
    }

    /**
     * Returns the set of ExpiryScanner instances.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * If there is no pre-instantiated instance set and classloading or instantiation of one or more
     * elements fails, this method will log an appropriate warning and return a non-null set with
     * fewer elements.
     *
     * @return the set of ExpiryScanner instances.
     */
    public List<ExpiryScanner> getExpiryScanners() {
        synchronized (this) {
            if (expiryScanners == null) {
                List<ExpiryScanner> instances = ClassloadingUtility.loadAndInstantiateClasses(ExpiryScanner.class, expiryScannerClassNames);
                expiryScanners = instances;
            }
            return new ArrayList<ExpiryScanner>(expiryScanners);
        }
    }

    /**
     * Sets the instances of ExpiryScanner.
     * The provided list will be copied, not retained.
     *
     * @param expiryScanners the set of ExpiryScanner instances.
     */
    public void setExpiryScanners(List<ExpiryScanner> expiryScanners) {
        synchronized (this) {
            if (expiryScanners == null) {
                this.expiryScanners = new ArrayList<ExpiryScanner>();
                this.expiryScannerClassNames = new ArrayList<String>();
            } else {
                this.expiryScanners = new ArrayList<ExpiryScanner>(expiryScanners);
                List<String> names = ClassloadingUtility.getNamesForClasses(this.expiryScanners);
                this.expiryScannerClassNames = names;
            }
        }
    }

    /**
     * Returns a list of names of classes that implement RecoveryModule.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * Default: empty list.
     * Equivalent deprecated property prefix:
     *
     * @return a list of RecoveryModule implementation class names.
     */
    public List<String> getRecoveryModuleClassNames() {
        synchronized (this) {
            return new ArrayList<String>(recoveryModuleClassNames);
        }
    }

    /**
     * Sets the recovery modules.
     * List elements should be names of classes that implement RecoveryModule.
     * The provided list will be copied, not retained.
     *
     * @param recoveryModuleClassNames a list of RecoveryModule implementation class names.
     */
    public void setRecoveryModuleClassNames(List<String> recoveryModuleClassNames) {
        synchronized (this) {
            if (recoveryModuleClassNames == null) {
                this.recoveryModules = new ArrayList<RecoveryModule>();
                this.recoveryModuleClassNames = new ArrayList<String>();
            } else if (!recoveryModuleClassNames.equals(this.recoveryModuleClassNames)) {
                this.recoveryModules = null;
                this.recoveryModuleClassNames = new ArrayList<String>(recoveryModuleClassNames);
            }
        }
    }

    /**
     * Returns the set of RecoveryModule instances.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * If there is no pre-instantiated instance set and classloading or instantiation of one or more
     * elements fails, this method will log an appropriate warning and return a non-null set with
     * fewer elements.
     *
     * @return the set of RecoveryModule instances.
     */
    public List<RecoveryModule> getRecoveryModules() {
        synchronized (this) {
            if (recoveryModules == null) {
                List<RecoveryModule> instances = ClassloadingUtility.loadAndInstantiateClassesWithInit(RecoveryModule.class, recoveryModuleClassNames);
                recoveryModules = instances;
            }
            return new ArrayList<RecoveryModule>(recoveryModules);
        }
    }

    /**
     * Sets the instances of RecoveryModule.
     * The provided list will be copied, not retained.
     *
     * @param recoveryModules the set of RecoveryModule instances.
     */
    public void setRecoveryModules(List<RecoveryModule> recoveryModules) {
        synchronized (this) {
            if (recoveryModules == null) {
                this.recoveryModules = new ArrayList<RecoveryModule>();
                this.recoveryModuleClassNames = new ArrayList<String>();
            } else {
                this.recoveryModules = new ArrayList<RecoveryModule>(recoveryModules);
                List<String> names = ClassloadingUtility.getNamesForClasses(this.recoveryModules);
                this.recoveryModuleClassNames = names;
            }
        }
    }


    /**
     * Returns a list of names of classes that implement RecoveryActivator.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * Default: empty list.
     * Equivalent deprecated property prefix:
     *
     * @return a list of RecoveryActivator implementation class names.
     */
    public List<String> getRecoveryActivatorClassNames() {
        synchronized (this) {
            return new ArrayList<String>(recoveryActivatorClassNames);
        }
    }

    /**
     * Sets the recovery activators.
     * List elements should be names of classes that implement RecoveryActivator.
     * The provided list will be copied, not retained.
     *
     * @param recoveryActivatorClassNames a list of RecoveryActivator implementation class names.
     */
    public void setRecoveryActivatorClassNames(List<String> recoveryActivatorClassNames) {
        synchronized (this) {
            if (recoveryActivatorClassNames == null) {
                this.recoveryActivators = new ArrayList<RecoveryActivator>();
                this.recoveryActivatorClassNames = new ArrayList<String>();
            } else if (!recoveryActivatorClassNames.equals(this.recoveryActivatorClassNames)) {
                this.recoveryActivators = null;
                this.recoveryActivatorClassNames = new ArrayList<String>(recoveryActivatorClassNames);
            }
        }
    }

    /**
     * Returns the set of RecoveryActivator instances.
     * The returned list is a copy. May return an empty list, will not return null.
     * <p>
     * If there is no pre-instantiated instance set and classloading or instantiation of one or more
     * elements fails, this method will log an appropriate warning and return a non-null set with
     * fewer elements.
     *
     * @return the set of RecoveryActivator instances.
     */
    public List<RecoveryActivator> getRecoveryActivators() {
        synchronized (this) {
            if (recoveryActivators == null) {
                List<RecoveryActivator> instances = ClassloadingUtility.loadAndInstantiateClassesWithInit(RecoveryActivator.class, recoveryActivatorClassNames);
                recoveryActivators = instances;
            }
            return new ArrayList<RecoveryActivator>(recoveryActivators);
        }
    }

    /**
     * Sets the instances of RecoveryActivator.
     * The provided list will be copied, not retained.
     *
     * @param recoveryActivators the set of RecoveryActivator instances.
     */
    public void setRecoveryActivators(List<RecoveryActivator> recoveryActivators) {
        synchronized (this) {
            if (recoveryActivators == null) {
                this.recoveryActivators = new ArrayList<RecoveryActivator>();
                this.recoveryActivatorClassNames = new ArrayList<String>();
            } else {
                this.recoveryActivators = new ArrayList<RecoveryActivator>(recoveryActivators);
                List<String> names = ClassloadingUtility.getNamesForClasses(this.recoveryActivators);
                this.recoveryActivatorClassNames = names;
            }
        }
    }

    /**
     * Returns true if SO_TIMEOUT should be set on Listener socket instances.
     * <p>
     * Default: false
     * Equivalent deprecated property: com.arjuna.ats.internal.arjuna.recovery.listener.timeoutsocket
     *
     * @return true if SO_TIMEOUT should be used, false if not.
     */
    public boolean isTimeoutSocket() {
        return timeoutSocket;
    }

    /**
     * Sets the socket timeout behaviour of the Listener instances.
     *
     * @param timeoutSocket true to enable timeouts, false to disable.
     */
    public void setTimeoutSocket(boolean timeoutSocket) {
        this.timeoutSocket = timeoutSocket;
    }

    /**
     * <p>This method gives information about the behaviour of
     * {@link com.arjuna.ats.arjuna.recovery.RecoveryManager} when suspending.
     * <p>The current list of Narayana-provided RecoveryModules that support this feature is:
     * <ul>
     *    <li>com.arjuna.ats.internal.arjuna.recovery.AtomicActionRecoveryModule</li>
     * </ul>
     *
     * @return true if {@link com.arjuna.ats.arjuna.recovery.RecoveryManager} should wait that all
     * {@link RecoveryModule} overriding {@link RecoveryModule#hasWorkLeftToDo()} recover all their
     * transactions before shutting down; false otherwise.
     */
    @Deprecated(forRemoval = true)
    public boolean isWaitForWorkLeftToDo() {
        return waitForWorkLeftToDo;
    }

    /**
     * Configure the suspension of {@link com.arjuna.ats.arjuna.recovery.RecoveryManager}
     *
     * @param waitForWorkLeftToDo true if {@link com.arjuna.ats.arjuna.recovery.RecoveryManager} should
     * wait that all {@link RecoveryModule} overriding {@link RecoveryModule#hasWorkLeftToDo()} recover
     * all their transactions before shutting down; false otherwise.
     */
    @Deprecated(forRemoval = true)
    public void setWaitForWorkLeftToDo(boolean waitForWorkLeftToDo) {
        this.waitForWorkLeftToDo = waitForWorkLeftToDo;
    }

    /**
     * <p>This method gives information about the behaviour of
     * {@link com.arjuna.ats.arjuna.recovery.RecoveryManager} when suspending.
     * <p>In particular, heuristic transactions will be waited upon in case the graceful suspension
     * of {@link com.arjuna.ats.arjuna.recovery.RecoveryManager#suspend(boolean, boolean)} is active
     *
     * @return true if {@link com.arjuna.ats.arjuna.recovery.RecoveryManager} should wait that all
     * heuristic transactions have been resolved before suspending; false otherwise.
     */
    public boolean isWaitForHeuristicDuringSuspension() {
        return this.waitForHeuristicDuringSuspension;
    }

    /**
     * Configure how {@link com.arjuna.ats.arjuna.recovery.RecoveryManager}'s suspension should behave
     * when there are heuristic transactions in the object store.
     *
     * @param waitForHeuristicDuringSuspension true if {@link com.arjuna.ats.arjuna.recovery.RecoveryManager}
     * should wait that all heuristic transactions have been resolved before suspending; false otherwise.
     */
    public void setWaitForHeuristicDuringSuspension(boolean waitForHeuristicDuringSuspension) {
        this.waitForHeuristicDuringSuspension = waitForHeuristicDuringSuspension;
    }
}