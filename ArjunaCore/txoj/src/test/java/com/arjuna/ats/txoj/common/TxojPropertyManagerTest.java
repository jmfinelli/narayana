package com.arjuna.ats.txoj.common;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

public class TxojPropertyManagerTest {

	@Test
	public void testConfigFileNotRequired() {
		String propertyName = "com.arjuna.ats.txoj.common.TxojEnvironmentBean.allowNestedLocking";
		String originalPropertyValue = System.getProperty(propertyName);
		System.setProperty(propertyName, originalPropertyValue != null ? originalPropertyValue : "false");
		TxojEnvironmentBean txojEnvironmentBean = txojPropertyManager.getTxojEnvironmentBean();
		try {
			assertFalse(txojEnvironmentBean.isAllowNestedLocking());
		} finally {
			if (originalPropertyValue != null) {
				System.setProperty(propertyName, originalPropertyValue);
			} else {
				System.clearProperty(propertyName);
			}
		}
	}

}
