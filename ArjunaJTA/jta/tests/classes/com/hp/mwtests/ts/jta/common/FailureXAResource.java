/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package com.hp.mwtests.ts.jta.common;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

public class FailureXAResource implements XAResource {
    public enum FailLocation {
        none,
        prepare,
        commit,
        rollback,
        end,
        prepare_and_rollback
    }

    public enum FailType {
        normal,
        timeout,
        heurcom,
        nota,
        inval,
        proto,
        rmfail,
        rollback,
        XA_RBCOMMFAIL,
        XA_HEURHAZ,
        message,
        XA_RBINTEGRITY
    }

    public FailureXAResource() {
        this(FailLocation.none, FailType.normal);
    }

    public FailureXAResource(FailLocation loc) {
        this(loc, FailType.normal);
    }

    public FailureXAResource(FailLocation loc, FailType type) {
        _locale = loc;
        _type = type;
    }

    public void commit(Xid id, boolean onePhase) throws XAException {
        if (_locale == FailLocation.commit) {
            switch (_type) {
                case normal:
                    throw new XAException(XAException.XA_HEURMIX);
                case heurcom:
                    throw new XAException(XAException.XA_HEURCOM);
                case rollback:
                    throw new XAException(XAException.XA_HEURRB);
                case nota:
                    throw new XAException(XAException.XAER_NOTA);
                case inval:
                    throw new XAException(XAException.XAER_INVAL);
                case proto:
                    throw new XAException(XAException.XAER_PROTO);
                case rmfail:
                    throw new XAException(XAException.XAER_RMFAIL);
                case XA_RBINTEGRITY:
                    throw new XAException(XAException.XA_RBINTEGRITY);
                default:
                    throw new XAException(XAException.XA_RBTIMEOUT);
            }
        }
    }

    public void end(Xid xid, int flags) throws XAException {
        if (_locale == FailLocation.end) {
            switch (_type) {
                case normal:
                    throw new XAException(XAException.XA_HEURRB);
                case timeout:
                    throw new XAException(XAException.XA_RBTIMEOUT);
                case XA_RBCOMMFAIL:
                    throw new XAException(XAException.XA_RBCOMMFAIL);
            }
        }
    }

    public void forget(Xid xid) throws XAException {
    }

    public int getTransactionTimeout() throws XAException {
        return 0;
    }

    public boolean isSameRM(XAResource xares) throws XAException {
        return false;
    }

    public int prepare(Xid xid) throws XAException {
        if ((_locale == FailLocation.prepare) || (_locale == FailLocation.prepare_and_rollback)) {
            switch (_type) {
                case message:
                    XAException xae = new XAException(XAException.XA_RBROLLBACK);
                    xae.initCause(new Throwable("test message"));
                    throw xae;
                case XA_RBINTEGRITY:
                    throw new XAException(XAException.XA_RBINTEGRITY);
                case XA_HEURHAZ: // XA spec invalid error code
                    throw new XAException(XAException.XA_HEURHAZ);
                default:
                    throw new XAException(XAException.XAER_INVAL);
            }
        }

        return XA_OK;
    }

    public Xid[] recover(int flag) throws XAException {
        return null;
    }

    public void rollback(Xid xid) throws XAException {
        if ((_locale == FailLocation.rollback) || (_locale == FailLocation.prepare_and_rollback)) {
            switch (_type) {
                case normal:
                    throw new XAException(XAException.XA_HEURMIX);
                case heurcom:
                    throw new XAException(XAException.XA_HEURCOM);
                case rollback:
                    throw new XAException(XAException.XA_HEURRB);
                case nota:
                    throw new XAException(XAException.XAER_NOTA);
                case inval:
                    throw new XAException(XAException.XAER_INVAL);
                case proto:
                    throw new XAException(XAException.XAER_PROTO);
                case rmfail:
                    throw new XAException(XAException.XAER_RMFAIL);
                case XA_RBINTEGRITY:
                    throw new XAException(XAException.XAER_NOTA);
                default:
                    throw new XAException(XAException.XA_HEURHAZ);
            }
        }
    }

    public boolean setTransactionTimeout(int seconds) throws XAException {
        return true;
    }

    public void start(Xid xid, int flags) throws XAException {
    }

    private final FailLocation _locale;
    private final FailType _type;
}