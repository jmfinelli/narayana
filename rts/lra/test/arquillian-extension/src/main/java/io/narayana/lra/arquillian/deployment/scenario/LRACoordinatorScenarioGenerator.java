/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021, Red Hat, Inc., and individual contributors
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

package io.narayana.lra.arquillian.deployment.scenario;

import org.jboss.arquillian.container.spi.client.deployment.DeploymentDescription;
import org.jboss.arquillian.container.spi.client.deployment.TargetDescription;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentScenarioGenerator;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LRACoordinatorScenarioGenerator extends ScenarioGeneratorBase implements DeploymentScenarioGenerator {

    public static final String EXTENSION_NAME = "LRADeployment";
    public static final String EXTENSION_DEPLOYMENT_METHOD = "deploymentMethod"; // needed
    public static final String EXTENSION_DEPLOYMENT_NAME = "deploymentName"; // needed
    // public static final String EXTENSION_GROUP_NAME = "groupName"; // needed
    public static final String EXTENSION_CONTAINER_NAME = "containerName"; // needed

    @Override
    public List<DeploymentDescription> generate(TestClass testClass) {

        List<DeploymentDescription> descriptions = new ArrayList<>();

        // Fetch all properties in the section EXTENSION_NAME
        Map<String, String> extensionProperties = getExtensionProperties(EXTENSION_NAME);

        // Checks that all required properties are defined
        checkPropertiesExistence(
                extensionProperties,
                Arrays.asList(
                        EXTENSION_DEPLOYMENT_METHOD,
                        EXTENSION_DEPLOYMENT_NAME,
                        // EXTENSION_GROUP_NAME,
                        EXTENSION_CONTAINER_NAME));

        try {
            Method deploymentMethod = getDeploymentMethodFromConfiguration(extensionProperties, EXTENSION_DEPLOYMENT_METHOD);
            // GroupDef group = getGroupWithName(extensionProperties.get(EXTENSION_GROUP_NAME));
            // String containerName = getContainerWithName(group, extensionProperties.get(EXTENSION_CONTAINER_NAME)).getContainerName();
            String containerName = getContainerWithName(extensionProperties.get(EXTENSION_CONTAINER_NAME)).getContainerName();

            // Instantiates an object of the specified class (using the default constructor)
            Object obj = deploymentMethod.getDeclaringClass().getDeclaredConstructor().newInstance(null);
            WebArchive archive = (WebArchive) deploymentMethod.invoke(obj, extensionProperties.get(EXTENSION_DEPLOYMENT_NAME));
            DeploymentDescription deploymentDescription =
                    new DeploymentDescription(extensionProperties.get(EXTENSION_DEPLOYMENT_NAME), archive)
                            .setTarget(new TargetDescription(containerName));
            deploymentDescription.shouldBeTestable(false);
            deploymentDescription.shouldBeManaged(false);
            descriptions.add(deploymentDescription);
        }
        catch (ClassNotFoundException | NoSuchMethodException ex) {
            String message = String.format(
                    "%s: an exception occurred while looking for the deployment method: %s.",
                    EXTENSION_NAME,
                    extensionProperties.get(EXTENSION_DEPLOYMENT_METHOD));

            log.error(message);
            throw new RuntimeException(message);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            String message = String.format(
                    "%s: an exception occurred while invoking the deployment method: %s.",
                    EXTENSION_NAME,
                    extensionProperties.get(EXTENSION_DEPLOYMENT_METHOD));

            log.error(message);
            throw new RuntimeException(message);
        } catch (InstantiationException e) {
            String message = String.format(
                    "%s: an exception occurred while creating an instance of the class specified in the %s property.",
                    EXTENSION_NAME,
                    extensionProperties.get(EXTENSION_DEPLOYMENT_METHOD));

            log.error(message);
            throw new RuntimeException(message);
        }

        return descriptions;
    }
}
