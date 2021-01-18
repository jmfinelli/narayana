package io.narayana.lra.coordinator.setup;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;

import io.narayana.lra.coordinator.util.MgmtTestBase;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

public class DeactivateJDBCObjectStore extends AbstractServerSetupTask {

    private static final String DS_JNDI_NAME = "java:jboss/datasources/TestDatasource";
    private static final String DS_NAME = "TestDatasource";

    private static final ModelNode DS_ADDRESS = new ModelNode().add(SUBSYSTEM, "datasources").add("data-source", DS_NAME);
    private static final ModelNode TRANSACTIONS_ADDRESS = new ModelNode().add(SUBSYSTEM, "transactions");

    @Override
    public boolean doSetup(final ManagementClient managementClient) throws Exception {

        ModelControllerClient client = managementClient.getControllerClient();

        return deactivateJDBCObjectStore(client);
    }

    @Override
    public void undoSetup(ManagementClient managementClient) throws Exception {
        super.undoSetup(managementClient);
    }

    private boolean deactivateJDBCObjectStore(ModelControllerClient client) throws Exception {

        boolean restart = false;

        // Force Narayana to use the default Object Store (FileSystem)
        ModelNode result = client.execute(MgmtTestBase.remove(
                TRANSACTIONS_ADDRESS,
                "jdbc-store-datasource"));

        if (!result.get("response-headers").asString().equals("undefined")) {
            restart |= result.get("response-headers").get("operation-requires-restart").asBoolean();
        }

        result = client.execute(MgmtTestBase.writeAttribute(
                TRANSACTIONS_ADDRESS,
                "use-jdbc-store",
                "false"));

        if (!result.get("response-headers").asString().equals("undefined")) {
            restart |= result.get("response-headers").get("operation-requires-restart").asBoolean();
        }

        return restart;
    }

}
