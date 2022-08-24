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

package com.arjuna.ats.arjuna.tools.log;

import com.arjuna.ats.arjuna.AtomicAction;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.internal.arjuna.tools.log.EditableAtomicAction;
import com.arjuna.ats.internal.arjuna.tools.log.EditableTransaction;

import java.util.HashMap;

/*
 * A default implementation for the default logstore implementation.
 */

class AtomicActionTypeMap implements TransactionTypeManager.TransactionTypeMap {
    private static final String _type = new AtomicAction().type();

    public EditableTransaction getTransaction(final Uid u) {
        return new EditableAtomicAction(u);
    }

    public String getType() {
        return "AtomicAction";
    }

    public String getRealType() {
        return _type;
    }
}

public class TransactionTypeManager {
    private static final TransactionTypeManager _manager = new TransactionTypeManager();
    private HashMap<String, TransactionTypeMap> _maps = new HashMap<String, TransactionTypeMap>();

    private TransactionTypeManager() {
        addTransaction(new AtomicActionTypeMap());
    }

    public static TransactionTypeManager getInstance() {
        return _manager;
    }

    public EditableTransaction getTransaction(final String type, final Uid u) {
        if (type == null)
            throw new IllegalArgumentException();

        TransactionTypeMap map = _maps.get(type);

        if (map != null)
            return map.getTransaction(u);
        else
            return null;
    }

    public String getTransactionType(final String type) {
        if (type == null)
            throw new IllegalArgumentException();

        TransactionTypeMap map = _maps.get(type);

        if (map != null)
            return map.getRealType();
        else
            return null;
    }

    /**
     * Is this transaction log one we support?
     *
     * @param type the name of the log.
     *
     * @return true if supported, false otherwise.
     */

    public boolean present(final String type) {
        return (_maps.get(type) != null);
    }

    /*
     * All log implementations that we want to support should be in here
     * so they are registered. We could use a dynamic approach, but we don't
     * have a rapidly growing number of object stores anyway to justify that
     * overhead.
     */

    public void addTransaction(TransactionTypeMap map) {
        if (map == null)
            throw new IllegalArgumentException();

        _maps.put(map.getType(), map);
    }

    public void removeTransaction(String type) {
        if (type == null)
            throw new IllegalArgumentException();

        _maps.remove(type);
    }

    /**
     * Only allows the movement of heuristic participants to the prepared list.
     * Maybe allow general editing of both lists, including bidirectional
     * movement (point?) and deletion.
     */

    public interface TransactionTypeMap {
        public EditableTransaction getTransaction(final Uid u);

        public String getType(); // the shorthand name (could be the same as getRealType iff we want people to write really loooong strings).

        public String getRealType();  // the real type (used by object store operations)
    }
}
