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

import io.narayana.lra.arquillian.resource.JaxRsActivator;
import io.narayana.lra.arquillian.resource.LRAListener;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.logging.LRALogger;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static io.narayana.lra.arquillian.resource.LRAListener.LRA_LISTENER_KILL;
import static io.narayana.lra.arquillian.resource.LRAListener.LRA_LISTENER_UNTIMED_ACTION;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * This test class checks that an LRA, which times out while there is no running coordinator,
 * is cancelled when a coordinator is restarted
 */
@RunAsClient
public class LRACoordinatorRecoveryIT extends UnmanagedTestBase {

    private ResteasyClient client;
    private NarayanaLRAClient lraClient;
    private static final String CONTAINER_QUALIFIER = "wildfly_lra-coordinator";
    private static final String LRA_COORDINATOR_DEPLOYMENT_QUALIFIER = "lra-coordinator";
    private static final String SERVICE_DEPLOYMENT_QUALIFIER = "service";
    private static Path storeDir;

    private static final Long LONG_TIMEOUT = 600000L; // 10 minutes
    private static final Long SHORT_TIMEOUT = 10000L; // 10 seconds

    @Rule
    public TestName testName = new TestName();

    @BeforeClass
    public static void beforeClass() {
        storeDir = Paths.get(String.format("%s/standalone/data/tx-object-store", System.getenv("JBOSS_HOME")));
    }

    @Before
    public void before() {
        LRALogger.logger.debugf("Starting test %s", testName);

        client = new ResteasyClientBuilder().build();
        lraClient = new NarayanaLRAClient();
        startContainer(CONTAINER_QUALIFIER,
                SERVICE_DEPLOYMENT_QUALIFIER, LRA_COORDINATOR_DEPLOYMENT_QUALIFIER);
    }

    @After
    public void after() {
        if (client != null) {
            client.close();
        }

        clearRecoveryLog();

        stopContainer(CONTAINER_QUALIFIER,
                LRA_COORDINATOR_DEPLOYMENT_QUALIFIER, SERVICE_DEPLOYMENT_QUALIFIER);
    }

    @Deployment(name = SERVICE_DEPLOYMENT_QUALIFIER, testable = false, managed = false)
    @TargetsContainer(CONTAINER_QUALIFIER)
    public static WebArchive deploy() {
        return Deployer.createDeployment(SERVICE_DEPLOYMENT_QUALIFIER, JaxRsActivator.class, LRAListener.class);
    }

    @Test
    public void recoveryShortTimeoutLRA(@ArquillianResource @OperateOnDeployment(SERVICE_DEPLOYMENT_QUALIFIER) URL baseURL)
            throws URISyntaxException {

        String lraId;
        URI lraListenerURI = UriBuilder.fromUri(baseURL.toURI()).path(LRAListener.LRA_LISTENER_PATH).build();

        // start an LRA with a short time limit by invoking a resource annotated with @LRA
        // and then the invoked resource kills the container.
        try {
            Response response = client
                    .target(lraListenerURI)
                    .path(LRAListener.LRA_LISTENER_ACTION_KILL)
                    .request()
                    .put(null);

            Assert.assertEquals("LRA participant action", 200, response.getStatus());
            lraId = response.readEntity(String.class);

            fail(testName.getMethodName() + ": the container should have been killed by the invoked method");
        } catch (RuntimeException e) {
            LRALogger.logger.infof("%s: the container was killed successfully!", testName.getMethodName());
            // we could have started the LRA via lraClient (which we do in the next test) but it is useful to test the filters
            lraId = getFirstLRA();
            assertNotNull("A new LRA should have been added to the object store before the JVM was halted.", lraId);
            lraId = String.format("%s/%s", lraClient.getCoordinatorUrl(), lraId);
        }

        restartContainer(CONTAINER_QUALIFIER);

        // Waits for a period of time longer than the timeout of the LRA Transaction
        doWait(LRAListener.LRA_SHORT_TIMELIMIT * 1000);

        // check recovery
        LRAStatus status = getStatus(new URI(lraId));

        LRALogger.logger.infof("%s: Status after restart is %s%n", status == null ? "GONE" : status.name());

        if (status == null || status == LRAStatus.Cancelling) {
            int sc = recover();

            if (sc != 0) {
                recover();
            }
        }

        // the LRA with the short timeout should have timed out and cancelled
        status = getStatus(new URI(lraId));

        Assert.assertTrue(String.format("LRA %s should have cancelled", lraId),
                status == null || status == LRAStatus.Cancelled);

        // verify that the resource was notified that the LRA finished
        String listenerStatus = getStatusFromListener(lraListenerURI);

        assertEquals(String.format("The service LRAlistener should have been told that the final state of the LRA %s was cancelled", lraId),
                LRAStatus.Cancelled.name(), listenerStatus);
    }

    @Test
    public void recoveryTwoLRAs(@ArquillianResource @OperateOnDeployment(SERVICE_DEPLOYMENT_QUALIFIER) URL deploymentUrl)
            throws URISyntaxException, InterruptedException {

        URI lraListenerURI = UriBuilder.fromUri(deploymentUrl.toURI()).path(LRAListener.LRA_LISTENER_PATH).build();

        // start an LRA with a long timeout to validate that timed LRAs do not finish early during recovery
        URI longLRA = lraClient.startLRA(null, "Long Timeout Recovery Test", LONG_TIMEOUT, ChronoUnit.MILLIS);
        // start an LRA with a short timeout to validate that timed LRAs that time out when the coordinator is unavailable are cancelled
        URI shortLRA = lraClient.startLRA(null, "Short Timeout Recovery Test", SHORT_TIMEOUT, ChronoUnit.MILLIS);

        // invoke a method that will trigger a byteman rule to kill the JVM
        try (Response ignore = client.target(lraListenerURI).path(LRA_LISTENER_KILL)
                .request()
                .get()) {

            fail(testName + ": the container should have halted");
        } catch (RuntimeException e) {
            LRALogger.logger.infof("%s: container halted", testName);
        }

        // restart the container
        restartContainer(CONTAINER_QUALIFIER);

        // waiting for the short LRA timeout really expires
        doWait(SHORT_TIMEOUT);

        // check that on restart an LRA whose deadline has expired are cancelled
        int sc = recover();

        if (sc != 0) {
            recover();
        }

        LRAStatus longStatus = getStatus(longLRA);
        LRAStatus shortStatus = getStatus(shortLRA);

        Assert.assertEquals("LRA with long timeout should still be active",
                LRAStatus.Active.name(), longStatus.name());
        Assert.assertTrue("LRA with short timeout should not be active",
                shortStatus == null ||
                        LRAStatus.Cancelled.equals(shortStatus) || LRAStatus.Cancelling.equals(shortStatus));

        // verify that it is still possible to join in with the LRA
        try (Response response = client.target(lraListenerURI).path(LRA_LISTENER_UNTIMED_ACTION)
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, longLRA)
                .put(Entity.text(""))) {

            Assert.assertEquals("LRA participant action", 200, response.getStatus());
        }

        // closing the LRA and clearing the active thread of the launched LRAs
        lraClient.closeLRA(longLRA);
        lraClient.closeLRA(shortLRA);

        // check that the participant was notified that the LRA has closed
        String listenerStatus = getStatusFromListener(lraListenerURI);

        assertEquals("LRA listener should have been told that the final state of the LRA was closed",
                LRAStatus.Closed.name(), listenerStatus);
    }

    /*****************************/
    /** Class's private methods **/
    /*****************************/

    private void doWait(long millis) {
        if (millis > 0L) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                LRALogger.logger.errorf("An exception has been thrown while the test was trying to wait for %d milliseconds", millis);
                Assert.fail();
            }
        }
    }

    private int recover() {
        Client client = ClientBuilder.newClient();

        // This delay makes sure that the 2-phase recovery mechanism has started
        doWait(LRAListener.LRA_SHORT_TIMELIMIT * 1000);

        try (Response response = client.target(lraClient.getRecoveryUrl())
                .request()
                .get()) {

            Assert.assertEquals("Unexpected status from recovery call to " + lraClient.getRecoveryUrl(), 200, response.getStatus());

            // the result will be a List<LRAStatusHolder> of recovering LRAs but we just need the count
            String recoveringLRAs = response.readEntity(String.class);

            return recoveringLRAs.length() - recoveringLRAs.replace(".", "").length();
        } finally {
            client.close();
        }
    }

    private LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        }
    }

    /**
     * Asks {@link LRAListener} if it has been notified of the final outcome of the LRA
     * @return the listener's view of the LRA status
     */
    private String getStatusFromListener(URI lraListenerURI) {
        try (Response response = client.target(lraListenerURI).path(LRAListener.LRA_LISTENER_STATUS)
                .request()
                .get()) {

            Assert.assertEquals("LRA participant HTTP status", 200, response.getStatus());

            return response.readEntity(String.class);
        }
    }

    /**
     * <p>This method fetches the first LRA transaction from the File System Object Store (ShadowNoFileLockStore).
     * The FS Object Store is used as this is the default option of Narayana and, therefore, the default
     * option when Narayana is used as a module (e.g. Wildfly)</p>
     * @return The ID of the first LRA transaction
     */
    String getFirstLRA() {
        Path lraDir = Paths.get(storeDir.toString(), "ShadowNoFileLockStore", "defaultStore", LongRunningAction.getType());

        try {
            Optional<Path> lra = Files.list(new File(lraDir.toString()).toPath()).findFirst();

            return lra.map(path -> path.getFileName().toString()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * <p>This method physically deletes the folder (and all its content) where the File System Object Store
     * is stored. As specified in the JavaDoc of <code>getFirstLRA()</code>, the FS Object Store is used as
     * it is the default option of Narayana.</p>
     */
    private void clearRecoveryLog() {
        try (Stream<Path> recoveryLogFiles = Files.walk(storeDir)) {
            recoveryLogFiles
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ioe) {
            // transaction logs will only exists after there has been a previous run
            LRALogger.logger.debugf(ioe,"Cannot finish delete operation on recovery log dir '%s'", storeDir);
        }
    }
}
