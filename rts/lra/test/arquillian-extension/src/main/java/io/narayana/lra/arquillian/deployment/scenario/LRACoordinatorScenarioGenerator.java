/*
 * Copyright Red Hat
 * SPDX-License-Identifier: LGPL-2.1-only
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

        // If the section of this extension is not in the arquillian.xml file, it means that this extension
        // does not need to start. As a consequence, an empty list of DeploymentDescription is returned
        if (extensionProperties == null) {
            return new ArrayList<>();
        }

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
        } catch (ClassNotFoundException | NoSuchMethodException ex) {
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
