package io.narayana.lra.arquillian;

import io.narayana.lra.arquillian.resource.LRAListener;
import io.narayana.lra.logging.LRALogger;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import static org.junit.Assert.assertNotNull;

public class LRACoordinatorRecoveryIT extends UnmanagedTestBase {

    private ResteasyClient client;
    private static final String CONTAINER_QUALIFIER = "wildfly_unmanaged";
    private static final String DEPLOYMENT_QUALIFIER = "lra-coordinator";

    @Rule
    public TestName testName = new TestName();

    @Before
    public void before() throws MalformedURLException, URISyntaxException {
        LRALogger.logger.debugf("Starting test %s", testName);

        client = new ResteasyClientBuilder().build();
        startContainer(CONTAINER_QUALIFIER, DEPLOYMENT_QUALIFIER);
    }

    @After
    public void after() {
        if (client != null) {
            client.close();
        }

        stopContainer(CONTAINER_QUALIFIER,DEPLOYMENT_QUALIFIER);
    }

//    @Deployment(name = DEPLOYMENT_QUALIFIER, testable = false, managed = false)
//    public static WebArchive deploy() {
//        return Deployer.createDeployment(LRACoordinatorRecoveryIT.class.getSimpleName());
//    }

    @Test
    public void createLRA(@ArquillianResource @OperateOnDeployment(DEPLOYMENT_QUALIFIER) URL baseURL) throws URISyntaxException {
        URI lra = invokeInTransaction(baseURL.toURI(), LRAListener.LRA_LISTENER_PATH, LRAListener.LRA_LISTENER_ACTION, 200);

        assertNotNull(lra);
    }

    /*****************************/
    /** Class's private methods **/
    /*****************************/

    private URI invokeInTransaction(URI baseURL, String resourcePrefix, String resourcePath, int expectedStatus) {
        Response response = null;

        try {
            response = client.target(baseURL)
                    .path(resourcePrefix)
                    .path(resourcePath)
                    .request()
                    .get();

            Assert.assertTrue("Expecting a non empty body in response from " + resourcePrefix + "/" + resourcePath,
                    response.hasEntity());

            String entity = response.readEntity(String.class);

            Assert.assertEquals(
                    "response from " + resourcePrefix + "/" + resourcePath + " was " + entity,
                    expectedStatus, response.getStatus());

            return new URI(entity);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("response cannot be converted to URI: " + e.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }
}
