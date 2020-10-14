package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractPublishToIoTCoreOperationHandler extends OperationContinuationHandler<PublishToIoTCoreRequest, PublishToIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractPublishToIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<PublishToIoTCoreRequest, PublishToIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getPublishToIoTCoreModelContext();
  }
}
