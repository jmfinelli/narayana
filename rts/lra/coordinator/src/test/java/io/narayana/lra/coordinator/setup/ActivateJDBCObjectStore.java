package io.narayana.lra.coordinator.setup;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

import io.narayana.lra.coordinator.util.MgmtTestBase;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;

public class ActivateJDBCObjectStore extends AbstractServerSetupTask {

    private static final String DS_JNDI_NAME = "java:jboss/datasources/TestDatasource";
    private static final String DS_NAME = "TestDatasource";

    private static final ModelNode DS_ADDRESS = new ModelNode().add(SUBSYSTEM, "datasources").add("data-source", DS_NAME);
    private static final ModelNode TRANSACTIONS_ADDRESS = new ModelNode().add(SUBSYSTEM, "transactions");

    @Override
    public boolean doSetup(final ManagementClient managementClient) throws Exception {

        ModelControllerClient client = managementClient.getControllerClient();

        createDatasource(client);
        return activateJDBCObjectStore(client);

    }

    @Override
    public void undoSetup(ManagementClient managementClient) throws Exception {
        super.undoSetup(managementClient);

        //ModelControllerClient client = managementClient.getControllerClient();

        //deactivateJDBCObjectStore(client);
        //removeDatasource(client);

        //mgmtUtil.restart(client);
    }

    private void createDatasource(ModelControllerClient client) throws Exception {

        ModelNode addOperation = new ModelNode();
        addOperation.get(OP).set(ADD);
        addOperation.get(OP_ADDR).set(DS_ADDRESS);
        addOperation.get("jndi-name").set(DS_JNDI_NAME);
        addOperation.get("driver-name").set("h2");
        addOperation.get("statistics-enabled").set("true");
        addOperation.get("enabled").set("true");
        addOperation.get("user-name").set("sa");
        addOperation.get("password").set("sa");
        //addOperation.get("connection-url").set("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        addOperation.get("connection-url").set("jdbc:h2:${jboss.home.dir}/standalone/data/test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        addOperation.get("use-java-context").set("true");
        addOperation.get("jta").set("false");

        client.execute(addOperation);
    }

    private boolean activateJDBCObjectStore(ModelControllerClient client) throws Exception {

        // Force Narayana to use JDBC as Object Store
        ModelNode addDataSource = MgmtTestBase.writeAttribute(
                TRANSACTIONS_ADDRESS,
                "jdbc-store-datasource",
                DS_JNDI_NAME);

        ModelNode activateJDBC = MgmtTestBase.writeAttribute(
                TRANSACTIONS_ADDRESS,
                "use-jdbc-store",
                "true");

        ModelNode composite = new ModelNode();
        composite.get(OP).set(COMPOSITE);
        composite.get(OP_ADDR).setEmptyList();
        final ModelNode steps = composite.get(ModelDescriptionConstants.STEPS);
        steps.add(addDataSource);
        steps.add(activateJDBC);
        ModelNode result = client.execute(composite);

        if (!result.get("response-headers").asString().equals("undefined")) {
            return result.get("response-headers").get("operation-requires-restart").asBoolean();
        }

        return false;
    }

    private ModelNode removeDatasource(ModelControllerClient client) throws Exception {

        ModelNode removeOperation = new ModelNode();
        removeOperation.get(OP).set(REMOVE);
        removeOperation.get(OP_ADDR).set(DS_ADDRESS);
        return client.execute(removeOperation);
    }

    private void deactivateJDBCObjectStore(ModelControllerClient client) throws Exception {

        client.execute(MgmtTestBase.writeAttribute(
                TRANSACTIONS_ADDRESS,
                "use-jdbc-store",
                "false"));
    }

}
