package io.narayana.lra.coordinator.setup;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

import java.io.IOException;
import java.util.function.Function;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.*;

public class DeactivateJDBCObjectStore extends DMRTaskBase implements Function<ManagementClient, Boolean> {

    private static final ModelNode TRANSACTIONS_ADDRESS = new ModelNode().add(SUBSYSTEM, "transactions");

    @Override
    public Boolean apply(ManagementClient managementClient) {

        ModelControllerClient client = managementClient.getControllerClient();

        try {
            return deactivateJDBCObjectStore(client);
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean deactivateJDBCObjectStore(ModelControllerClient client) throws IOException {

        // Force Narayana to use the default Object Store (FileSystem)
        ModelNode changeDefaultObjectStore = DMRTaskBase.remove(
                TRANSACTIONS_ADDRESS,
                "jdbc-store-datasource");

        ModelNode deactivateJDBCObjectStore = DMRTaskBase.writeAttribute(
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
