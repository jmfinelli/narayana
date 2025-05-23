/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package com.arjuna.ats.internal.jta.transaction.arjunacore.jca;

import jakarta.resource.spi.XATerminator;
import jakarta.transaction.TransactionManager;

import com.arjuna.ats.jta.logging.jtaLogger;

/**
 * Utility factory class to return appropriate implementations of the TransactionImporter and
 * XATerminator interfaces. This will automatically instantiate the correct implementations
 * based on the use of the JTA or JTAX transaction manager configuration and is therefore
 * preferred to instantiating a specific implementation class directly.
 */
public class SubordinationManager
{
    public enum TxType { JTA, JTS }    
    
    private static TransactionImporter transactionImporter = null;
    private static XATerminator xaTerminator = null;
    private static TxType txType;

    public static TransactionImporter getTransactionImporter()
    {
        if(transactionImporter == null)
        {
            initTransactionImporter();
        }
        
        return transactionImporter;
    }

    public static XATerminator getXATerminator()
    {
        if(xaTerminator == null)
        {
            initXATerminator();
        }
        
        return xaTerminator;
    }

    public static TxType getTxType()
    {
        return txType;
    }

    public static void setTxType(TxType txType)
    {
		if (jtaLogger.logger.isTraceEnabled()) {
            jtaLogger.logger.trace("SubordinationManager.setTxType(" + txType + ")");
        }
        
        if(SubordinationManager.txType != null && SubordinationManager.txType != txType)
        {
            throw new IllegalStateException("SubordinationManager can't change txType once it has been set.");
        }
        
        SubordinationManager.txType = txType;
    }

    private static void initTransactionImporter()
    {
        if(txType == null) {
            setTxType( guessTxType() );
        }
        
        if(txType == TxType.JTA)
        {
            // we are running in JTA mode
            transactionImporter = new TransactionImporterImple();
        }
        else
        {
            // it's not JTA, so it must be JTAX. However, we are in the JTA module and
            // can't link against the JTS code so we need to do it the hard way...
            try
            {
                Class clazz = Class.forName("com.arjuna.ats.internal.jta.transaction.jts.jca.TransactionImporterImple");
                transactionImporter = (TransactionImporter)clazz.getDeclaredConstructor().newInstance();
            }
            catch(Exception e)
            {
                jtaLogger.i18NLogger.error_transaction_arjunacore_jca_SubordinationManager_importerfailure(e);
            }
        }
    }
    
    private static void initXATerminator()
    {
        if(txType == null) {
            setTxType( guessTxType() );
        }

        TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
        
        if(txType == TxType.JTA)
        {
            // we are running in JTA mode
            xaTerminator = new XATerminatorImple();
        }
        else
        {
            // it's not JTA, so it must be JTAX. However, we are in the JTA module and
            // can't link against the JTS code so we need to do it the hard way...
            try
            {
                Class clazz = Class.forName("com.arjuna.ats.internal.jta.transaction.jts.jca.XATerminatorImple");
                xaTerminator = (XATerminator)clazz.getDeclaredConstructor().newInstance();
            }
            catch(Exception e)
            {
                jtaLogger.i18NLogger.error_transaction_arjunacore_jca_SubordinationManager_terminatorfailure(e);
            }
        }
    }

    /**
     * Its rather tricky to figure out if we are running in JTA or JTAX(JTS) mode. We can make a reasonable guess
     * based on the transaction manager implementation that is running, but it's going to break if some unknown
     * or derived impl comes along. It's therefore safer to use setTxType explicitly in such cases.
     * 
     * @return best guess at the currently configured TxType.
     */
    private static TxType guessTxType() {
        TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
        
        if(tm instanceof com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionManagerImple)
        {
            return TxType.JTA;
        } else if (tm.getClass().getName().contains(".jts.") || tm.getClass().getName().contains(".jtax.")) {
            return TxType.JTS;
        } else {
            return TxType.JTA;
        }
    }
}