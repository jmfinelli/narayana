/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package com.arjuna.ats.internal.jta.tools.osb.mbean.jta;

import com.arjuna.ats.internal.arjuna.tools.osb.annotation.MXBeanPropertyDescription;
import com.arjuna.ats.internal.arjuna.tools.osb.mbean.ActionBeanMBean;

public interface RecoverConnectableAtomicActionBeanMBean extends ActionBeanMBean {
    @MXBeanPropertyDescription("A unique id for this transaction")
	String toDo();
}