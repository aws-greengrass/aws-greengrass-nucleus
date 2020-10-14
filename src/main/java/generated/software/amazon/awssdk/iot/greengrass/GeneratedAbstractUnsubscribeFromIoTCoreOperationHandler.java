package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler extends OperationContinuationHandler<UnsubscribeFromIoTCoreRequest, UnsubscribeFromIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<UnsubscribeFromIoTCoreRequest, UnsubscribeFromIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getUnsubscribeFromIoTCoreModelContext();
  }
}
