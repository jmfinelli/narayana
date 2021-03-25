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

        Arrays.stream(deploymentQualifiers).forEach(x -> deployer.deploy(x));
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

            Arrays.stream(deploymentQualifiers).forEach(x -> deployer.undeploy(x));

            containerController.stop(containerQualifier);
        }
    }
}
