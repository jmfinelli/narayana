/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package com.arjuna.ats.arjuna.recovery;

/**
 * <p>
 *     When a Recovery Module implements this interface, it can veto the suspension
 *     of the Recovery Manager.
 * </p>
 * <p>
 *     Note for the implementer:
 *     While the Recovery Manager is suspending, Recovery Modules
 *     (implementing SuspendBlockingRecoveryModule) that indicate they do not want
 *     to block recovery cannot change their decision to want to block recovery
 *     in subsequent recovery cycles.
 *     In other words, during the suspension of the Recovery Manager, once a
 *     Recovery Module (implementing SuspendBlockingRecoveryModule)
 *     switches from `shouldBlockShutdown() == true` to `shouldBlockShutdown() == false`,
 *     it cannot change its mind.
 * </p>
 */
public interface SuspendBlockingRecoveryModule extends RecoveryModule {

    /**
     * <p>
     *     This method returns true when the Recovery Manager should block its
     *     suspension, false otherwise.
     * </p>
     * <p>
     *     Note: This method should be invoked only at the end of the recovery cycle,
     *     i.e. at the end of the second pass. Any invocation that happens before that
     *     point does not guarantee to return the correct value.
     * </p>
     *
     * @return whether this implementation of SuspendBlockingRecoveryModule
     * should block the suspension of the Recovery Manager or not
     */
    default boolean shouldBlockSuspension() {
        return false;
    }
}
