package io.narayana.lra.arquillian;

import io.narayana.lra.logging.LRALogger;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.runner.RunWith;

/**
 * This class is the test base to manually manage Arquillian containers.
 * All test classes in this modules should extend this class.
 */

@RunWith(Arquillian.class)
@RunAsClient
public class UnmanagedTestBase {

    @ArquillianResource
    private ContainerController containerController;

    @ArquillianResource
    private Deployer deployer;

    void startContainer(String containerQualifier, String deploymentQualifier) {

        containerController.start(containerQualifier);
        deployer.deploy(deploymentQualifier);
    }

    void restartContainer(String containerQualifier) {
        try {
            // ensure that the controller is not running
            containerController.kill(containerQualifier);
            LRALogger.logger.debugf("Container %s was killed successfully", containerController);
        } catch (Exception e) {
            LRALogger.logger.errorf("There was an error killing the container %s: %s", containerQualifier, e.getMessage());
        }

        containerController.start(containerQualifier);
    }

    void stopContainer(String containerQualifier, String deploymentQualifier) {
        if (containerController.isStarted(containerQualifier)) {
            LRALogger.logger.debugf("Stopping container %s", containerQualifier);

            deployer.undeploy(deploymentQualifier);

            containerController.stop(containerQualifier);
            containerController.kill(containerQualifier);
        }
    }
}
