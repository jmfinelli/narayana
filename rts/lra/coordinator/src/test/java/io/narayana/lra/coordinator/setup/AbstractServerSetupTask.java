/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
package io.narayana.lra.coordinator.setup;

import io.narayana.lra.coordinator.util.MgmtTestBase;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.dmr.ModelNode;

/**
 * Implementation of ServerSetupTask for JCA related tests
 *
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 */

public abstract class AbstractServerSetupTask implements ServerSetupTask {

    public static boolean restartNeeded = false;
    private static final ModelNode DEPLOYMENTS_ADDRESS = new ModelNode().add(SUBSYSTEM, "deployments").add("deployment", DS_NAME);

    @Override
    public final void setup(final ManagementClient managementClient, final String containerId) throws Exception {
        AbstractServerSetupTask.restartNeeded = doSetup(managementClient);
    }

    @Override
    public final void tearDown(ManagementClient managementClient, String containerId) throws Exception {
        undoSetup(managementClient);
    }

    public abstract boolean doSetup(final ManagementClient managementClient) throws Exception;

    public void undoSetup(final ManagementClient managementClient) throws Exception {
        unDeployment(managementClient);
    }

    private void unDeployment(final ManagementClient managementClient) throws Exception {
        managementClient.getControllerClient().execute(MgmtTestBase.undeploy(DEPLOYMENTS_ADDRESS));
    }

}
