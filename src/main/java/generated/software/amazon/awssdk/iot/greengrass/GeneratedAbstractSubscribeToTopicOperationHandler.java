package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToTopicRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToTopicResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscriptionResponseMessage;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToTopicOperationHandler extends OperationContinuationHandler<SubscribeToTopicRequest, SubscribeToTopicResponse, EventStreamJsonMessage, SubscriptionResponseMessage> {
  protected GeneratedAbstractSubscribeToTopicOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToTopicRequest, SubscribeToTopicResponse, EventStreamJsonMessage, SubscriptionResponseMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToTopicModelContext();
  }
}
