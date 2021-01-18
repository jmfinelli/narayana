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

import io.narayana.lra.Current;
import io.narayana.lra.LRAData;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipantRegistry;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.coordinator.setup.AbstractServerSetupTask;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.arquillian.container.test.api.Config;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import com.arjuna.ats.arjuna.recovery.RecoveryModule;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

public abstract class AbstractLRATestMgmt {

    private static final Package[] coordinatorPackages = {
            RecoveryModule.class.getPackage(),
            Coordinator.class.getPackage(),
            LRAData.class.getPackage(),
            LRAStatus.class.getPackage(),
            LRALogger.class.getPackage(),
            NarayanaLRAClient.class.getPackage(),
            Current.class.getPackage(),
            LRAService.class.getPackage(),
            LRARecoveryModule.class.getPackage(),
            LongRunningAction.class.getPackage()
    };

    private static final Package[] participantPackages = {
            LRAListener.class.getPackage(),
            LRA.class.getPackage(),
            ServerLRAFilter.class.getPackage(),
            LRAParticipantRegistry.class.getPackage()
    };

    @Deployment(name = COORDINATOR_DEPLOYMENT, testable = false, managed = false)
    public static WebArchive createDeployment() {
        // LRA uses ArjunaCore so pull in the jts module to get them on the classpath
        // (maybe in the future we can add a WFLY LRA subsystem)
        final String ManifestMF = "Manifest-Version: 1.0\n"
                + "Dependencies: org.jboss.jts, org.jboss.logging\n";
        return ShrinkWrap.create(WebArchive.class, COORDINATOR_DEPLOYMENT + ".war")
                .addPackages(false, coordinatorPackages)
                .addPackages(false, participantPackages)
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    protected static final String COORDINATOR_CONTAINER = "lra-coordinator";
    public static final String COORDINATOR_DEPLOYMENT = COORDINATOR_CONTAINER;
    protected NarayanaLRAClient lraClient;

    @Rule
    public TestName testName = new TestName();

    @ArquillianResource
    private ContainerController containerController;

    @ArquillianResource
    private Deployer deployer;

    @Before
    public void before() throws URISyntaxException, MalformedURLException {

        // restart the container
        if (AbstractServerSetupTask.restartNeeded) {
            restartContainer();
            AbstractServerSetupTask.restartNeeded = false;
        }

        LRALogger.logger.debugf("Starting test %s", testName);
        lraClient = new NarayanaLRAClient();
    }

    @After
    public void after() {
        LRALogger.logger.debugf("Finished test %s", testName);
        lraClient.close();
    }

    void startContainer(String bytemanScript) {
        Config config = new Config();
        String javaVmArguments = System.getProperty("server.jvm.args");

        if (bytemanScript != null) {
            String testClassesDir = System.getProperty("maven.test.classes.dir");
            javaVmArguments = javaVmArguments.replaceAll("=listen", "=script:" + testClassesDir + "/scripts/@BMScript@.btm,listen");
            javaVmArguments = javaVmArguments.replace("@BMScript@", bytemanScript);
        }

        config.add("javaVmArguments", javaVmArguments);

        containerController.start(COORDINATOR_CONTAINER, config.map());
        deployer.deploy(COORDINATOR_DEPLOYMENT);
    }

    void restartContainer() {
        try {
            // ensure that the controller is not running
            //containerController.kill(COORDINATOR_CONTAINER);
            if (containerController.isStarted(COORDINATOR_CONTAINER))
                containerController.stop(COORDINATOR_CONTAINER);
            LRALogger.logger.debug("jboss-as kill worked");
        } catch (Exception e) {
            LRALogger.logger.debugf("jboss-as kill: %s", e.getMessage());
        }

        Config config = new Config();
        String javaVmArguments = System.getProperty("server.jvm.args");
        config.add("javaVmArguments", javaVmArguments);
        containerController.start(COORDINATOR_CONTAINER);
    }

    void stopContainer() {
        if (containerController.isStarted(COORDINATOR_CONTAINER)) {
            LRALogger.logger.debug("Stopping container");

            deployer.undeploy(COORDINATOR_DEPLOYMENT);

            containerController.stop(COORDINATOR_CONTAINER);
            //containerController.kill(COORDINATOR_CONTAINER);
        }
    }

    int recover() {
        Client client = ClientBuilder.newClient();

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

    void doWait(long millis) throws InterruptedException {
        if (millis > 0L) {
            Thread.sleep(millis);
        }
    }

    LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        }
    }

    abstract void clearRecoveryLog();

    abstract String getFirstLRA();
}
