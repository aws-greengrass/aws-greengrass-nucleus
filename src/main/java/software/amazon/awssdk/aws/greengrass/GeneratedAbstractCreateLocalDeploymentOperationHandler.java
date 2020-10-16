package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentRequest;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractCreateLocalDeploymentOperationHandler extends OperationContinuationHandler<CreateLocalDeploymentRequest, CreateLocalDeploymentResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractCreateLocalDeploymentOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<CreateLocalDeploymentRequest, CreateLocalDeploymentResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getCreateLocalDeploymentModelContext();
  }
}
