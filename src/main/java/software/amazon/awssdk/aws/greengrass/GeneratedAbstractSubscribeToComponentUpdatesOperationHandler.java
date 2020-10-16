package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToComponentUpdatesOperationHandler extends OperationContinuationHandler<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamJsonMessage, ComponentUpdatePolicyEvents> {
  protected GeneratedAbstractSubscribeToComponentUpdatesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamJsonMessage, ComponentUpdatePolicyEvents> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToComponentUpdatesModelContext();
  }
}
