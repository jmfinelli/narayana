/*
 * Copyright 2014, Red Hat Middleware LLC, and individual contributors
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
 * (C) 2014
 * @author JBoss Inc.
 */
package com.arjuna.ats.internal.arjuna.recovery;

import com.arjuna.ats.arjuna.common.Uid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Exposes the current status of the periodic recovery thread as reported by {@link RecoveryManagerImple#trySuspendScan(boolean)}
 */
public class RecoveryManagerStatus {

    public enum State {

        /**
         * state value indicating an undefined state ({@link PeriodicRecovery.Mode#UNKNOWN})
         */
        UNKNOWN,

        /**
         * state value indicating that new scans may proceed ({@link PeriodicRecovery.Mode#ENABLED})
         */
        ENABLED,

        /**
         * state value indicating that new scans may not proceed and the periodic recovery thread should
         * suspend ({@link PeriodicRecovery.Mode#SUSPENDED})
         */
        SUSPENDED,

        /**
         * state value indicating that new scans may not proceed and that the singleton
         * PeriodicRecovery thread instance should exit if it is still running ({@link PeriodicRecovery.Mode#TERMINATED})
         */
        TERMINATED;
    }

    private final Collection<Uid> _uids;

    private final PeriodicRecovery.Mode _state;

    public RecoveryManagerStatus(PeriodicRecovery.Mode state) {
        this(state, null);
    }

    public RecoveryManagerStatus(PeriodicRecovery.Mode state, Collection<Uid> uidsLeftInTheStore) {
        _state = state;

        if (Objects.isNull(uidsLeftInTheStore)) {
            _uids = new ArrayList<>();
        } else {
            _uids = new ArrayList<>(uidsLeftInTheStore);
        }
    }

    public Collection<Uid> getUids() {
        return Collections.unmodifiableCollection(_uids);
    }

    public State getState() {
        switch (_state) {
            case ENABLED:
                return State.ENABLED;
            case SUSPENDED:
                return State.SUSPENDED;
            case TERMINATED:
                return State.TERMINATED;
            default:
                return State.UNKNOWN;
        }
    }
}
