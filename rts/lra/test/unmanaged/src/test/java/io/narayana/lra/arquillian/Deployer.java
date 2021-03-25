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

import io.narayana.lra.AnnotationResolver;
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

    private static final Package[] participantPackages = {
            LRA.class.getPackage(),
            LRAStatus.class.getPackage(),
            LRAMetricService.class.getPackage(),
            ServerLRAFilter.class.getPackage(),
            AnnotationResolver.class.getPackage(),
            LRALogger.class.getPackage(),
            NarayanaLRAClient.class.getPackage(),
            LRAParticipantRegistry.class.getPackage()
    };

    public static WebArchive createDeployment(String appName, Class<?> ...classes) {

        return ShrinkWrap.create(WebArchive.class, appName + ".war")
                .addPackages(true, LRAMetricService.class.getPackage())
                .addPackages(false, participantPackages)
                .addClasses(LRAClientOps.class, WrongHeaderException.class)
                .addClasses(classes)
                .addAsManifestResource(
                        new StringAsset("<beans version=\"1.1\" bean-discovery-mode=\"annotated\"></beans>"),
                        "beans.xml");
    }
}
