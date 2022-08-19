/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
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
package com.arjuna.common.tests.propertyservice;

import java.util.Properties;

import com.arjuna.common.util.propertyservice.AbstractPropertiesFactory;
import com.arjuna.common.util.propertyservice.PropertiesFactorySax;
import com.arjuna.common.util.propertyservice.PropertiesFactoryStax;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.arjuna.common.tests.propertyservice.PropertiesFactoryUtil.assertProperties;

/**
 *
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 *
 */
public final class PropertiesFactoryTest {
    private Properties expectedProperties;

    @BeforeEach
    public void setUp() {
        expectedProperties = PropertiesFactoryUtil.getExpectedProperties();

        // property not defined in XML file but defined as system property should be found as well
        System.setProperty("CoordinatorEnvironmentBean.dynamic1PC", "false");
        expectedProperties.put("CoordinatorEnvironmentBean.dynamic1PC", "false");
    }

    @Test
    public void testGetDefaultPropertiesWithStax() {
        final AbstractPropertiesFactory propertiesFactory = new PropertiesFactoryStax();
        final Properties properties = propertiesFactory.getDefaultProperties();

        assertProperties(expectedProperties, properties);
    }

    @Test
    public void testGetDefaultPropertiesWithSax() {
        final AbstractPropertiesFactory propertiesFactory = new PropertiesFactorySax();
        final Properties properties = propertiesFactory.getDefaultProperties();

        assertProperties(expectedProperties, properties);
    }

}
