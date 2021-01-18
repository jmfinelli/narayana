package io.narayana.lra.coordinator.setup;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

import io.narayana.lra.coordinator.util.MgmtTestBase;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

public class DeactivateJDBCObjectStore extends AbstractServerSetupTask {

    private static final ModelNode TRANSACTIONS_ADDRESS = new ModelNode().add(SUBSYSTEM, "transactions");

    @Override
    public boolean doSetup(final ManagementClient managementClient) throws Exception {

        ModelControllerClient client = managementClient.getControllerClient();

        return deactivateJDBCObjectStore(client);
    }

    private boolean deactivateJDBCObjectStore(ModelControllerClient client) throws Exception {

        // Force Narayana to use the default Object Store (FileSystem)
        ModelNode changeDefaultObjectStore = MgmtTestBase.remove(
                TRANSACTIONS_ADDRESS,
                "jdbc-store-datasource");

        ModelNode deactivateJDBCObjectStore = MgmtTestBase.writeAttribute(
                TRANSACTIONS_ADDRESS,
                "use-jdbc-store",
                "false");

        ModelNode composite = new ModelNode();
        composite.get(OP).set(COMPOSITE);
        composite.get(OP_ADDR).setEmptyList();
        final ModelNode steps = composite.get(ModelDescriptionConstants.STEPS);
        steps.add(changeDefaultObjectStore);
        steps.add(deactivateJDBCObjectStore);
        ModelNode result = client.execute(composite);

        if (!result.get("response-headers").asString().equals("undefined")) {
            return result.get("response-headers").get("operation-requires-restart").asBoolean();
        }

        return false;
    }

}
