/*
 * Copyright Red Hat
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package io.narayana.lra.arquillian;

import io.narayana.lra.logging.LRALogger;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * This class is the test base to manually manage Arquillian containers.
 * All test classes in this modules should extend this class.
 */
@RunWith(Arquillian.class)
public class UnmanagedTestBase {

    @ArquillianResource
    private ContainerController containerController;

    @ArquillianResource
    private Deployer deployer;

    void startContainer(String containerQualifier, String... deploymentQualifiers) {

        if (!containerController.isStarted(containerQualifier)) {
            containerController.start(containerQualifier);
        }

        Arrays.stream(deploymentQualifiers)
                .filter(x -> !x.isEmpty())
                .forEach(x -> deployer.deploy(x));
    }

    void restartContainer(String containerQualifier) {
        try {
            if (containerController.isStarted(containerQualifier)) {
                // ensure that the controller is not running
                containerController.stop(containerQualifier);
                LRALogger.logger.debugf("Container %s was killed successfully", containerController);
            }
        } catch (Exception e) {
            LRALogger.logger.errorf("There was an error killing the container %s: %s", containerQualifier, e.getMessage());
        }

        containerController.start(containerQualifier);
    }

    void stopContainer(String containerQualifier, String... deploymentQualifiers) {
        if (containerController.isStarted(containerQualifier)) {
            LRALogger.logger.debugf("Stopping container %s", containerQualifier);

            Arrays.stream(deploymentQualifiers)
                    .filter(x -> !x.isEmpty())
                    .forEach(x -> deployer.undeploy(x));

            containerController.stop(containerQualifier);
        }
    }
}
