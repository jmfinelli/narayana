package com.arjuna.ats.arjuna.recovery;

/**
 * <p>
 *     If a Recovery Module implements this interface, it can veto the suspension
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

    default boolean shouldBlockShutdown() {
        return false;
    }
}
