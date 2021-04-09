package io.narayana.lra.arquillian.deployment;

import io.narayana.lra.arquillian.spi.NarayanaLRARecovery;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * Arquillian extension to generate a Deployment scenario to be used
 * uniquely in a THORNTAIL container.
 *
 * @author <a href="mailto:jfinelli@redhat.com">Jmanuel Finelli</a>
 */
public class ThorntailLRACoordinatorDeployment implements Deployment {

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
    public WebArchive create(String deploymentName) {
        WebArchive archive = ShrinkWrap.create(WebArchive.class)
                // adding LRA spec interfaces under the client test deployment
                .addPackages(true, org.eclipse.microprofile.lra.annotation.Compensate.class.getPackage())
                // adding whole Narayana LRA implementation under the client test deployment
                .addPackages(true, io.narayana.lra.LRAConstants.class.getPackage())
                // registration of LRACDIExtension as Weld extension to be booted-up
                .addAsResource("META-INF/services/javax.enterprise.inject.spi.Extension")
                .addClass(org.jboss.weld.exceptions.DefinitionException.class)
                // explicitly define to work with annotated beans
                .addAsManifestResource(new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"annotated\"></beans>"), "beans.xml");

        // adding Narayana LRA filters under the client test deployment
        String filtersAsset = String.format("%s%n%s",
                io.narayana.lra.filter.ClientLRAResponseFilter.class.getName(),
                io.narayana.lra.filter.ClientLRARequestFilter.class.getName());
        archive.addPackages(true, io.narayana.lra.filter.ClientLRARequestFilter.class.getPackage())
                .addAsResource(new StringAsset(filtersAsset), "META-INF/services/javax.ws.rs.ext.Providers")
                .addAsResource(new StringAsset("org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder"),
                        "META-INF/services/javax.ws.rs.client.ClientBuilder");

        // adding TCK required SPI implementations
        archive.addPackage(NarayanaLRARecovery.class.getPackage());
        archive.addAsResource(new StringAsset("io.narayana.lra.arquillian.spi.NarayanaLRARecovery"),
                "META-INF/services/org.eclipse.microprofile.lra.tck.service.spi.LRARecoveryService");

        return archive;
    }
}
