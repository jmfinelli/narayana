/*
 * Copyright Red Hat
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package io.narayana.lra.arquillian.deployment;

import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.formatter.Formatters;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;

/**
 * Arquillian extension to generate a Deployment scenario to be used
 * uniquely in a WILDFLY container.
 */
public class WildflyLRACoordinatorDeployment implements Deployment<WebArchive> {

    private final String DEFAULT_DEPLOYMENT_QUALIFIER = "lra-coordinator";
    private final Logger log = Logger.getLogger(WildflyLRACoordinatorDeployment.class);

    /**
     * This method produces a lra-coordinator {@link WebArchive}. In the Arquillian
     * deployment process, this WebArchive can be used to deploy the lra-coordinator
     * as web service.
     *
     * @return {@link WebArchive} to deploy the lra-coordinator module as a web service.
     */
    @Override
    public Archive<WebArchive> create(String deploymentName) {

        // Checks if deploymentName is not defined
        if (deploymentName == null || deploymentName.isEmpty()) {
            deploymentName = DEFAULT_DEPLOYMENT_QUALIFIER;
        }

        String resteasyClientVersion = System.getProperty("version.resteasy-client");
        String eclipseLraVersion = System.getProperty("version.microprofile.lra.api");
        String eclipseTckVersion = System.getProperty("version.microprofile.lra.tck");

        // Creates the WAR archive
        WebArchive war = ShrinkWrap.create(WebArchive.class, deploymentName + ".war")
                // Pulls in LRA coordinator.
                .addPackages(true, "io.narayana.lra.coordinator")
                .addPackages(false,
                        "io.narayana.lra",
                        "io.narayana.lra.logging")
                // Loads dependencies
                .addAsLibraries(Maven.resolver()
                        .resolve("org.jboss.resteasy:resteasy-client:" + resteasyClientVersion,
                                "org.eclipse.microprofile.lra:microprofile-lra-api:" + eclipseLraVersion,
                                "org.eclipse.microprofile.lra:microprofile-lra-tck:" + eclipseTckVersion)
                        .withoutTransitivity().asFile())
                // Adds a manifest to activate jts and logging submodules of Wildfly
                .addAsManifestResource(
                        new StringAsset("Dependencies: org.jboss.jts export services, org.jboss.logging\n"),
                        "MANIFEST.MF")
                // Adds an empty beans.xml to declare that this archive is a bean archive
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");

        if(log.isDebugEnabled()) {
            log.debugf("Content of the LRA Coordinator WAR is:%n%s%n", war.toString(Formatters.VERBOSE));
        }

        return war;
    }
}
