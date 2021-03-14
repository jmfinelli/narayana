package io.narayana.lra.arquillian.deployment;

import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;

import java.io.File;

public class LRACoordinatorDeployment {

    private static final String LRA_COORDINATOR_DEPLOYMENT_NAME = "lra-coordinator";
    private static final Logger log = Logger.getLogger(LRACoordinatorDeployment.class);

    /**
     * This method produces a {@link WebArchive} resolving the Maven dependency
     * <code>org.jboss.narayana.rts:lra-coordinator-war</code>. In the Arquillian
     * deployment process, this WebArchive can be used to create a web service in
     * the container.
     *
     * @return {@link WebArchive} resolving the Maven dependency <code>org.jboss.narayana.rts:lra-coordinator-war</code>
     */
    public static WebArchive createLRACoordinatorDeployment() {
        // LRA uses ArjunaCore - for WildFly we need to pull the org.jboss.jts module to get it on the classpath
        final String ManifestMF = "Manifest-Version: 1.0\n"
                + "Dependencies: org.jboss.jts, org.jboss.logging\n";

        String mavenProjectVersion = System.getProperty("project.version");

        File[] files = Maven.resolver()
                .resolve("org.jboss.narayana.rts:lra-coordinator-war:war:" + mavenProjectVersion)
                .withTransitivity().asFile();

        ZipImporter zip = ShrinkWrap.create(ZipImporter.class, LRA_COORDINATOR_DEPLOYMENT_NAME + ".war");
        for(File file: files) {
            zip.importFrom(file);
        }
        WebArchive war = zip.as(WebArchive.class);

        if(log.isDebugEnabled()) {
            log.debugf("Content of the LRA Coordinator WAR is:%n%s%n", war.toString(true));
        }
        return war;
    }
}
