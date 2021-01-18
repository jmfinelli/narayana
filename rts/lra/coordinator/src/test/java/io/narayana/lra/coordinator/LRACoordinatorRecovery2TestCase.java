/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019, Red Hat, Inc., and individual contributors
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
package io.narayana.lra.coordinator;

import io.narayana.lra.logging.LRALogger;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.temporal.ChronoUnit;

import static io.narayana.lra.coordinator.LRAListener.LRA_LISTENER_KILL;
import static io.narayana.lra.coordinator.LRAListener.LRA_LISTENER_STATUS;
import static io.narayana.lra.coordinator.LRAListener.LRA_LISTENER_UNTIMED_ACTION;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test that check that LRA deadlines are respected during crash recovery
 */
@RunWith(Arquillian.class)
@RunAsClient
public class LRACoordinatorRecovery2TestCase extends JDBCTestBaseImpl {
    private static final Long LONG_TIMEOUT = TimeoutValueAdjuster.adjustTimeout(600000L); // 10 minutes
    private static final Long SHORT_TIMEOUT = 10000L; // 10 seconds

    private Client client;

    @Before
    public void before() throws MalformedURLException, URISyntaxException {
        super.before();

        client = ClientBuilder.newClient();
        startContainer(null);
    }

    @After
    public void after() {
        client.close();
        stopContainer();
        super.after();
    }

    /**
     * Test that an LRA which times out while there is no running coordinator is cancelled
     * when a coordinator is restarted.
     *
     * Test that an LRA which times out after a coordinator is restarted after a crash is still active
     */
    @Test
    public void testRecovery2(@ArquillianResource @OperateOnDeployment(COORDINATOR_DEPLOYMENT) URL deploymentUrl) throws URISyntaxException, InterruptedException {

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
            LRALogger.logger.infof("%s: the container halted", testName);
        }

        // waiting for the short LRA timeout really expires
        Thread.sleep(TimeoutValueAdjuster.adjustTimeout(SHORT_TIMEOUT));

        // restart the container
        restartContainer();

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
        try {
            lraClient.closeLRA(shortLRA);
        } catch(Exception ex) {
            LRALogger.logger.infof("%s: LRA with short timeout was already closed the close action was called", shortLRA);
        }

        // check that the participant was notified that the LRA has closed
        String listenerStatus = getStatusFromListener(lraListenerURI);

        assertEquals("LRA listener should have been told that the final state of the LRA was closed",
                LRAStatus.Closed.name(), listenerStatus);
    }

    /**
     * Ask {@link LRAListener} if it has been notified of the final outcome of the LRA
     * @return the listeners view of the LRA status
     */
    private String getStatusFromListener(URI lraListenerURI) {
        try (Response response = client.target(lraListenerURI).path(LRA_LISTENER_STATUS)
                .request()
                .get()) {

            Assert.assertEquals("LRA participant HTTP status", 200, response.getStatus());

            return response.readEntity(String.class);
        }
    }
}
