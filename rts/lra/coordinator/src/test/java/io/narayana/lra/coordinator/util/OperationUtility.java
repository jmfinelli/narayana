package io.narayana.lra.coordinator.util;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 */

public final class OperationUtility {

    /**
     * Provide reload operation on server
     *
     * @throws Exception
     */
    public static void reload(ManagementClient client) throws Exception {
        ServerReload.executeReloadAndWaitForCompletion(client, 50000);
    }

    /**
     * Reads attribute from DMR model
     *
     * @param address       to read
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
     * @param address        to write
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

}