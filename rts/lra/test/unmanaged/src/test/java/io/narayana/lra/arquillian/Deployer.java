/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
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

import io.narayana.lra.Current;
import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.resource.LRAListener;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipantRegistry;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.tck.LRAClientOps;
import org.eclipse.microprofile.lra.tck.participant.api.WrongHeaderException;
import org.eclipse.microprofile.lra.tck.service.LRAMetricService;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

public class Deployer {

    private static final Package[] coordinatorPackages = {
            LRAData.class.getPackage(),
            LRAStatus.class.getPackage(),
            LRALogger.class.getPackage(),
            NarayanaLRAClient.class.getPackage(),
            Current.class.getPackage()
    };

    private static final Package[] participantPackages = {
            LRAListener.class.getPackage(),
            LRA.class.getPackage(),
            ServerLRAFilter.class.getPackage(),
            LRAParticipantRegistry.class.getPackage()
    };

    public static WebArchive deploy(String appName) {
        // manifest for WildFly deployment which requires access to transaction jboss module
        final String ManifestMF = "Manifest-Version: 1.0\n"
            + "Dependencies: org.jboss.jts, org.jboss.logging\n";

        return ShrinkWrap.create(WebArchive.class, appName + ".war")
                .addPackages(true, LRAMetricService.class.getPackage())
                .addClasses(LRAClientOps.class, WrongHeaderException.class)
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF");
    }

    public static WebArchive createDeployment(String appName) {
        // LRA uses ArjunaCore so pull in the jts module to get them on the classpath
        // (maybe in the future we can add a WFLY LRA subsystem)
        final String ManifestMF = "Manifest-Version: 1.0\n"
                + "Dependencies: org.jboss.jts, org.jboss.logging\n";

        return ShrinkWrap.create(WebArchive.class, appName + ".war")
                .addPackages(false, coordinatorPackages)
                .addPackages(false, participantPackages)
                .addAsManifestResource(new StringAsset(ManifestMF), "MANIFEST.MF")
                .addAsManifestResource(new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"annotated\"></beans>"), "beans.xml");
    }
}
