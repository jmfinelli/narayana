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

import io.narayana.lra.coordinator.TestBase;

import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implementation of ServerSetupTask for JCA related tests
 *
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 * Adapted for LRA integration testing by:
 * @author <a href="mailto:mfinelli@redhat.com">Manuel Finelli</a>
 */

public class ServerSetupTaskContext implements ServerSetupTask {

    public static boolean restartNeeded = false;

    private Function<ManagementClient, Boolean> doSetupFunction = new IdentityFunction();
    private Consumer<ManagementClient> undoSetupFunction = new IdentityConsumer();
    private final String objectStorePreference;

    public ServerSetupTaskContext() {

        // Fetches the property that indicates what
        // Object Store should be used
        this.objectStorePreference = System.getProperty("objectStorePreference");

        if (Objects.isNull(this.objectStorePreference) ||
                this.objectStorePreference.isEmpty() ||
                this.objectStorePreference.equals("filesystem")) {

            // This is the default option
            doSetupFunction = new DeactivateJDBCObjectStore();

        } else if (this.objectStorePreference.equals("jdbc")) {

            doSetupFunction = new ActivateJDBCObjectStore();
        }
    }

    @Override
    public final void setup(final ManagementClient managementClient, final String containerId) throws Exception {

        unDeployment(managementClient);
        ServerSetupTaskContext.restartNeeded = doSetupFunction.apply(managementClient);

    }

    @Override
    public final void tearDown(ManagementClient managementClient, String containerId) throws Exception {

        undoSetupFunction.accept(managementClient);
        unDeployment(managementClient);

    }

    private void unDeployment(final ManagementClient managementClient) throws Exception {
        managementClient.getControllerClient()
                .execute(
                        DMRTaskBase.undeploy(TestBase.COORDINATOR_DEPLOYMENT + ".war"));
    }

    private class IdentityConsumer implements Consumer<ManagementClient>  {

        @Override
        public void accept(ManagementClient managementClient) {
        }
    }

    private class IdentityFunction implements Function<ManagementClient, Boolean> {

        @Override
        public Boolean apply(ManagementClient managementClient) {
            return false;
        }
    }
}
