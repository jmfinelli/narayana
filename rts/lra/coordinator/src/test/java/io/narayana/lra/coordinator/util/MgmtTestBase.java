package io.narayana.lra.coordinator.util;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.test.integration.management.util.ServerReload;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 * @author <a href="mailto:jfinelli@redhat.com">Manuel Finelli</a>
 */

public final class MgmtTestBase {

    /**
     * Provide reload operation on server
     *
     * @throws Exception
     */
    public static void reload(ModelControllerClient client) throws Exception {
        ServerReload.executeReloadAndWaitForCompletion(client, 50000);
    }

    /**
     * Provide restart operation on server
     *
     * @throws Exception
     */
    public static ModelNode restart(ModelControllerClient client) throws Exception {
        final ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.SHUTDOWN);
        op.get(ModelDescriptionConstants.RESTART).set(true);
        return client.execute(op);
    }


    /**
     * Reads attribute from DMR model
     *
     * @param address to read
     * @param attributeName
     * @return attribute value
     * @throws Exception
     */
    public static final ModelNode readAttribute(ModelNode address, String attributeName) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION);
        op.get(ModelDescriptionConstants.NAME).set(attributeName);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }

    /**
     * Writes attribute value
     *
     * @param address to write
     * @param attributeName
     * @param attributeValue
     * @return result of operation
     * @throws Exception
     */
    public static final ModelNode writeAttribute(ModelNode address, String attributeName, String attributeValue) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION);
        op.get(ModelDescriptionConstants.NAME).set(attributeName);
        op.get(ModelDescriptionConstants.VALUE).set(attributeValue);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }

    /**
     * Remove Operation
     *
     * @param address to write
     * @param attributeName
     * @return result of operation
     * @throws Exception
     */
    public static final ModelNode remove(ModelNode address) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.REMOVE);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }

    /**
     * Remove Operation
     *
     * @param address to write
     * @param attributeName
     * @param attributeValue
     * @return result of operation
     * @throws Exception
     */
    public static final ModelNode remove(ModelNode address, String attributeName) throws Exception {
        ModelNode op = remove(address);
        op.get(ModelDescriptionConstants.NAME).set(attributeName);
        return op;
    }

    /**
     * Undeploy Operation
     *
     * @param address to write
     * @param attributeName
     * @return result of operation
     * @throws Exception
     */
    public static final ModelNode undeploy(ModelNode address) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.UNDEPLOY);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }
}