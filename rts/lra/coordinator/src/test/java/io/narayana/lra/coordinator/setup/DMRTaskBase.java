package io.narayana.lra.coordinator.setup;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 * @author <a href="mailto:jfinelli@redhat.com">Manuel Finelli</a>
 */

public class DMRTaskBase {

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
     * @param address
     * @param attributeName
     * @return ModelNode to be executed by the client
     */
    public static final ModelNode readAttribute(ModelNode address, String attributeName) {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION);
        op.get(ModelDescriptionConstants.NAME).set(attributeName);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }

    /**
     * Writes attribute value
     *
     * @param address
     * @param attributeName
     * @param attributeValue
     * @return ModelNode to be executed by the client
     */
    public static final ModelNode writeAttribute(ModelNode address, String attributeName, String attributeValue) {
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
     * @param address
     * @return ModelNode to be executed by the client
     */
    public static final ModelNode remove(ModelNode address) {
        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.REMOVE);
        op.get(ModelDescriptionConstants.OP_ADDR).set(address);
        return op;
    }

    /**
     * Remove Operation
     *
     * @param address
     * @return ModelNode to be executed by the client
     */
    public static final ModelNode remove(ModelNode address, String attributeName) {
        ModelNode op = remove(address);
        op.get(ModelDescriptionConstants.NAME).set(attributeName);
        return op;
    }

    /**
     * Undeploy Operation
     *
     * @param deploymentName
     * @return ModelNode to be executed by the client
     */
    public static final ModelNode undeploy(String deploymentName) {
      ModelNode undeployRequest = new ModelNode();
      undeployRequest.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.UNDEPLOY);
      undeployRequest.get(ModelDescriptionConstants.OP_ADDR, "deployment").set(deploymentName);

      ModelNode removeRequest = new ModelNode();
      removeRequest.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.REMOVE);
      removeRequest.get(ModelDescriptionConstants.OP_ADDR, "deployment").set(deploymentName);

      ModelNode composite = new ModelNode();
      composite.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
      composite.get(ModelDescriptionConstants.OP_ADDR).setEmptyList();
      final ModelNode steps = composite.get(ModelDescriptionConstants.STEPS);
      steps.add(undeployRequest);
      steps.add(removeRequest);
      return composite;
    }
}