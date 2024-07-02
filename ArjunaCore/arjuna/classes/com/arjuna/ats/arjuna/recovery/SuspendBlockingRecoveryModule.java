package com.arjuna.ats.arjuna.recovery;

/**
 * If a recovery module implements this interface it allows the recovery manager shutdown to block
 */
public interface SuspendBlockingRecoveryModule extends RecoveryModule {

    public default boolean shouldBlockShutdown() {
        return false;
    }
}
