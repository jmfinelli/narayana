package com.arjuna.ats.arjuna.recovery;

/**
 * If a recovery module implements this interface, it can veto the suspension of the Recovery Manager
 */
public interface SuspendBlockingRecoveryModule extends RecoveryModule {

    default boolean shouldBlockShutdown() {
        return false;
    }
}
