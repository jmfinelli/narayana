package com.arjuna.ats.arjuna.tools.osb.mbean;

import com.arjuna.ats.arjuna.tools.osb.annotation.MXBeanDescription;
import com.arjuna.ats.arjuna.tools.osb.annotation.MXBeanPropertyDescription;

import javax.management.MBeanException;

/**
 * @deprecated as of 5.0.5.Final In a subsequent release we will change packages names in order to
 *         provide a better separation between public and internal classes.
 */
@Deprecated // in order to provide a better separation between public and internal classes.
@MXBeanDescription("")
public interface OSEntryBeanMBean extends ObjStoreItemMBean {
    String getType();

    String getId();

    @MXBeanPropertyDescription("Tell the Transaction Manager to remove this record")
    String remove() throws MBeanException;
}
