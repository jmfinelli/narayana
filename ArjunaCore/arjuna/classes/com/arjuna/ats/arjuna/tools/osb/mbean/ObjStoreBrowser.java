/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.arjuna.ats.arjuna.tools.osb.mbean;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.management.MBeanException;

import com.arjuna.ats.arjuna.StateManager;
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.arjuna.objectstore.ObjectStoreIterator;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.tools.osb.util.JMXServer;

/**
 * An MBean implementation for walking an ObjectStore and creating/deleting MBeans
 * that represent completing transactions (ie ones on which the user has called commit)
 *
 * @author Mike Musgrove
 * @deprecated as of 5.0.5.Final In a subsequent release we will change packages names in order to
 * provide a better separation between public and internal classes.
 */
@Deprecated // in order to provide a better separation between public and internal classes.
public class ObjStoreBrowser implements ObjStoreBrowserMBean {

    private static final String SUBORDINATE_AA_TYPE =
            "StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction/SubordinateAtomicAction/JCA";


    private static final String SUBORDINATE_ATI_TYPE =
            "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/ServerTransaction/JCA";

    private static final OSBTypeHandler[] defaultOsbTypes = {
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.recovery.arjunacore.RecoverConnectableAtomicAction",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jta.RecoverConnectableAtomicActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/AtomicActionConnectable",
                    null
            ),
            new OSBTypeHandler(
                    false,
                    "com.arjuna.ats.internal.jta.transaction.arjunacore.subordinate.jca.SubordinateAtomicAction",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jta.SubordinateActionBean",
                    SUBORDINATE_AA_TYPE,
                    null
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.arjuna.AtomicAction",
                    "com.arjuna.ats.arjuna.tools.osb.mbean.ActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction",
                    null
            ),
    };

    private static final OSBTypeHandler[] defaultJTSOsbTypes = {
            new OSBTypeHandler(
                    true,
                    false, // by default do not probe for this type
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSXAResourceRecordWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSXAResourceRecordWrapper",
                    "CosTransactions/XAResourceRecord",
                    null
            ),
            new OSBTypeHandler(
                    false,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JCAServerTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    SUBORDINATE_ATI_TYPE,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JCAServerTransactionHeaderReader"
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ArjunaTransactionImpleWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple",
                    null
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/ServerTransaction",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionHeaderReader"
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/AssumedCompleteServerTransaction",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionHeaderReader"
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.RecoveredTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/AssumedCompleteHeuristicTransaction",
                    null
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/AssumedCompleteHeuristicServerTransaction",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.ServerTransactionHeaderReader"
            ),
            new OSBTypeHandler(
                    true,
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.RecoveredTransactionWrapper",
                    "com.arjuna.ats.internal.jta.tools.osb.mbean.jts.JTSActionBean",
                    "StateManager/BasicAction/TwoPhaseCoordinator/ArjunaTransactionImple/AssumedCompleteTransaction",
                    null
            )
    };

    private static final Map<String, OSBTypeHandler> osbTypeMap = new ConcurrentHashMap<>();

    // A system property for defining extra bean types for instrumenting object store types
    // The format is OSType1=BeanType1,OSType2=BeanType2,etc
    public static final String OBJ_STORE_BROWSER_HANDLERS = "com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowserHandlers";
    private final String objStoreBrowserMBeanName;

    public static HeaderStateReader getHeaderStateUnpacker(String type) {
        OSBTypeHandler osbType = osbTypeMap.get(type);

        return (osbType != null) ? osbType.getHeaderStateReader() : null;
    }

    // holder of type to Uid mapping
    private final Map<String, UidWrapper> registeredMBeans = new ConcurrentHashMap<>();
    private static volatile boolean isObjectStoreEmpty = true;
    private boolean exposeAllLogs = false;

    /**
     * Initialise the MBean
     */
    public void start()
    {
        JMXServer.getAgent().registerMBean(arjPropertyManager.getObjectStoreEnvironmentBean().getJmxToolingMBeanName(), this);
    }

    /**
     * Unregister all MBeans representing objects in the ObjectStore
     * represented by this MBean
     */
    public void stop()
    {
        unregisterMBeans();

        JMXServer.getAgent().unregisterMBean(arjPropertyManager.getObjectStoreEnvironmentBean().getJmxToolingMBeanName());
    }

    private List<UidWrapper> registeredMBeansByType(String type) {
        List<UidWrapper> returnList = registeredMBeans.values()
                .stream().filter(x -> type.equals(x.getType()))
                .collect(Collectors.toUnmodifiableList());

        return returnList.isEmpty() ? null : returnList;
    }

    private void unregisterMBeans(List<UidWrapper> beans) {
        for (UidWrapper w : beans) {
            w.unregister();
            registeredMBeans.remove(w.getUid().stringForm());
        }
    }

    protected void unregisterMBeans() {
        for (UidWrapper uid : Collections.unmodifiableCollection(registeredMBeans.values())) {
            uid.unregister();
            registeredMBeans.remove(uid.getUid().stringForm());
        }
    }

    private void registerMBeans() {
        for (UidWrapper uid : registeredMBeans.values()) {
            uid.register();
        }
    }

    /**
     * Returns the JMX name that the MBean that was registered with.
     * If the MBean was not yet registered then the method returns the name provided by configuration
     * within the {@link ObjectStoreEnvironmentBean#getJmxToolingMBeanName()}.
     *
     * @return name of the JMX
     */
    public String getObjStoreBrowserMBeanName() {
        return this.objStoreBrowserMBeanName;
    }

    /**
     * This method is deprecated in favour of {@link #setType(String, String)}.
     * The issue with this method is there is no mechanism for determining which class
     * is responsible for a given OS type.
     * This method is a no-action method.
     *
     * Define which object store types will be registered as MBeans
     * @param types the list of ObjectStore types that can be represented as MBeans
     */
    @Deprecated
    public void setTypes(Map<String, String> types) {
    }

    /**
     * Tell the object browser which beans to use for particular Object Store Action type
     *
     * @param osTypeClassName {@link StateManager} type class name
     * @param beanTypeClassName bean class name which makes tooling available on top of the {@code osTypeClassName}
     * @return whether the type was set OK
     */
    public boolean setType(String osTypeClassName, String beanTypeClassName) {
        try {
            Class<?> cls = Class.forName(osTypeClassName);
            StateManager sm = (StateManager) cls.getConstructor().newInstance();
            String typeName = canonicalType(sm.type());

            return addType(typeName, osTypeClassName, beanTypeClassName);
        } catch (Exception e) {
            tsLogger.i18NLogger.warn_invalidObjStoreBrowser_type(osTypeClassName, e);
            return false;
        }
    }

    /**
     * Add the type to be considered by object browser.
     * See {@link #setType(String, String)}.
     *
     * @param typeName type name that the bean will operate at
     * @param osTypeClassName {@link StateManager} type class name
     * @param beanTypeClassName bean class name which makes tooling available on top of the {@code osTypeClassName}
     * @return whether the type was set OK
     */
    public boolean addType(String typeName, String osTypeClassName, String beanTypeClassName) {
        if (typeName == null || typeName.length() == 0)
            return false;

        osbTypeMap.put(typeName, new OSBTypeHandler(true, osTypeClassName, beanTypeClassName, typeName, null));
        typeName = typeName.replaceAll("/", File.separator);
        osbTypeMap.put(typeName, new OSBTypeHandler(true, osTypeClassName, beanTypeClassName, typeName, null));
        return true;
    }

    /**
     * @param handler specification for handling object store types
     * @return the previous value associated with type handler, or null if there was no previous handler.
     */
    public OSBTypeHandler registerHandler(OSBTypeHandler handler) {
        return osbTypeMap.put(handler.getTypeName(), handler);
    }

    private void initTypeHandlers(String handlers) {
        for (String h : handlers.split(",")) {
            String[] handler = h.split("=");

            if (handler.length == 2) {
                setType(handler[0], handler[1]);
            }
        }
    }

    private void init(String logDir) {
        if (logDir != null)
            arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreDir(logDir);

        if (tsLogger.logger.isTraceEnabled())
            tsLogger.logger.trace("ObjectStoreDir: " + arjPropertyManager.getObjectStoreEnvironmentBean().getObjectStoreDir());

        setExposeAllRecordsAsMBeans(arjPropertyManager.
            getObjectStoreEnvironmentBean().getExposeAllLogRecordsAsMBeans());

        for (OSBTypeHandler osbType : defaultOsbTypes)
            osbTypeMap.put(osbType.getTypeName(), osbType);

        for (OSBTypeHandler osbType : defaultJTSOsbTypes)
            osbTypeMap.put(osbType.getTypeName(), osbType);

        initTypeHandlers(System.getProperty(OBJ_STORE_BROWSER_HANDLERS, ""));
    }

    /**
     * Constructor for default configuration of the object store browser.
     */
    public ObjStoreBrowser() {
        this(null);
    }

    public ObjStoreBrowser(String logDir) {
        init(logDir);
        this.objStoreBrowserMBeanName = arjPropertyManager.getObjectStoreEnvironmentBean().getJmxToolingMBeanName();
    }

    /**
     * Dump info about all registered MBeans
     * @param sb a buffer to contain the result
     * @return the passed in buffer
     */
    public StringBuilder dump(StringBuilder sb) {
        for (UidWrapper uid : registeredMBeans.values()) {
            uid.toString("\t", sb);
        }

        return sb;
    }

    /**
     * See if the given uid has previously been registered as an MBean
     * @param uid the unique id representing an ObjectStore entry
     * @return the MBean wrapper corresponding to the requested Uid (or null
     * if it hasn't been registered)
     */
    public UidWrapper findUid(Uid uid) {
        return registeredMBeans.getOrDefault(uid.stringForm(), null);
    }

    /**
     * Find the registered bean corresponding to a uid.
     *
     * @deprecated use {@link #findUid(com.arjuna.ats.arjuna.common.Uid)} instead.
     * @param uid the uid
     * @return the registered bean or null if the Uid is not registered
     */
    @Deprecated
    public UidWrapper findUid(String uid) {
        for (UidWrapper registeredUid : registeredMBeans.values()) {
            if (registeredUid.getUid().stringForm().equals(uid)) {
                return registeredUid;
            }
        }

        return null;
    }

    public void viewSubordinateAtomicActions(boolean enable) {

        List<String> records = new ArrayList<>();

        OSBTypeHandler subordinateAA = osbTypeMap.get(SUBORDINATE_AA_TYPE);

        if (subordinateAA != null) {
            subordinateAA.setEnabled(enable);
            records.add(subordinateAA.getRecordClass());
        }

        OSBTypeHandler subordinateATI = osbTypeMap.get(SUBORDINATE_ATI_TYPE);

        if (subordinateATI != null) {
            subordinateATI.setEnabled(enable);
            records.add(subordinateATI.getRecordClass());
        }

        if (!enable) {
            for (UidWrapper uid : Collections.unmodifiableCollection(registeredMBeans.values())) {
                if (records.contains(uid.getClassName())) {
                    registeredMBeans.remove(uid);
                    uid.unregister();
                }
            }
        }
    }

    public void setExposeAllRecordsAsMBeans(boolean exposeAllLogs) {
        this.exposeAllLogs = exposeAllLogs;
    }

    /**
     * Update registered MBeans based on the current set of Uids.
     * @param allCurrUids any registered MBeans not in this collection will be deregistered
     */
    private void unregisterRemovedUids(Map<String, Collection<Uid>> allCurrUids) {

        for (Map.Entry<String, Collection<Uid>> currUidsOfType : allCurrUids.entrySet()) {
            List<UidWrapper> registeredMBeansByType = registeredMBeansByType(currUidsOfType.getKey());
            if (Objects.nonNull(registeredMBeansByType)) {
                if (Objects.nonNull(currUidsOfType.getValue())) {
                    for (UidWrapper uid : registeredMBeansByType) {
                        if (!currUidsOfType.getValue().contains(uid.getUid())) {
                            uid.unregister();
                            registeredMBeans.remove(uid.getUid().stringForm());
                        }
                    }
                } else {
                    unregisterMBeans(registeredMBeansByType);
                }
            }
        }
    }

    /**
     * See if any new MBeans need to be registered or if any existing MBeans no longer exist
     * as ObjectStore entries.
     *
     * @throws MBeanException exception is thrown when it's not possible to list Uids from object store
     */
    public synchronized void probe() throws MBeanException {
        Map<String, Collection<Uid>> currUidsForType = new HashMap<>();

        for (String type : getTypes()) {
            currUidsForType.put(type, getUids(type));
        }

        // if there are any beans in registeredMBeans that don't appear in new list and unregister them
        unregisterRemovedUids(currUidsForType); //unregisterMBeans();

        for (Map.Entry<String, Collection<Uid>> e : currUidsForType.entrySet()) {
            String type = e.getKey();

            for (Uid uid : e.getValue()) {
                if (!registeredMBeans.containsKey(uid.stringForm())) {
                    UidWrapper w = createBean(uid, type); // can return null if type isn't instrumented

                    if (w != null)
                        registeredMBeans.put(uid.stringForm(), w);
                }
            }
        }

        isObjectStoreEmpty = registeredMBeans.isEmpty();

        /*
         * now create the actual MBeans - we create all the UidWrappers before registering because
         * the process of creating a bean can call back into the browser to probe for a particular type
         * (see for example com.arjuna.ats.arjuna.tools.osb.mbean.ActionBean)
         */
        registerMBeans();
    }

    /**
     * Register new MBeans of the requested type (or unregister ones whose
     * corresponding ObjectStore entry has been removed)
     * @param type the ObjectStore entry type
     * @return the list of MBeans representing the requested ObjectStore type
     */
    public List<UidWrapper> probe(String type) {
        type = canonicalType(type);

        return (type == null ? null : registeredMBeansByType(type));
    }

    /**
     * Check if the Object Store is empty
     *
     * @return true if the Object Store is empty, false otherwise
     * @throws MBeanException exception is thrown when it's not possible to list Uids from the object store
     */
    public boolean isObjectStoreEmpty() throws MBeanException {
        probe();
        synchronized (this) {
            return isObjectStoreEmpty;
        }
    }

    private UidWrapper createBean(Uid uid, String type) {
        OSBTypeHandler osbType = osbTypeMap.get(type);
        //boolean enabled = osbType == null || osbType.isEnabled();
        boolean registerBean = osbType != null && osbType.isAllowRegistration();

        if (osbType == null && !exposeAllLogs)
            return null;

        if (exposeAllLogs)
            registerBean = true;

//        if (osbType != null && !osbType.enabled)
//            return null;

        String beanType = osbType == null ? OSEntryBean.class.getName() : osbType.getBeanClass();
        String stateType = osbType == null ? null : osbType.getRecordClass();
        UidWrapper w = new UidWrapper(this, beanType, type, stateType, uid, registerBean);

        w.createMBean();

        return w;
    }

    private Collection<Uid> getUids(String type) throws MBeanException {
        Collection<Uid> uids = new ArrayList<>();
        try {
            ObjectStoreIterator iter = new ObjectStoreIterator(StoreManager.getRecoveryStore(), type);
    
            while (true) {
                Uid u = iter.iterate();
    
                if (u == null || Uid.nullUid().equals(u))
                    break;
    
                uids.add(u);
            }
        } catch (ObjectStoreException | IOException e) {
            throw new MBeanException(e);
        }

        return uids;
    }

    public static String canonicalType(String type) {
        if (type == null)
            return null;

        type = type.replace(File.separator, "/");

        while (type.startsWith("/"))
            type = type.substring(1);

        return type;
    }

    private Collection<String> getTypes() {
        Collection<String> allTypes = new ArrayList<>();
        InputObjectState types = new InputObjectState();

        try {
            if (StoreManager.getRecoveryStore().allTypes(types)) {

                while (true) {
                    try {
                        String typeName = canonicalType(types.unpackString());

                        if (typeName.length() == 0)
                            break;

                        allTypes.add(typeName);
                    } catch (IOException e1) {
                        break;
                    }
                }
            }
        } catch (ObjectStoreException e) {
            if (tsLogger.logger.isTraceEnabled())
                tsLogger.logger.trace(e.toString());
        }

        return allTypes;
    }
}
