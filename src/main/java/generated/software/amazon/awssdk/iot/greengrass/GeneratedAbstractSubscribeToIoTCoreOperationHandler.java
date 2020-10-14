package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.IoTCoreMessage;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToIoTCoreOperationHandler extends OperationContinuationHandler<SubscribeToIoTCoreRequest, SubscribeToIoTCoreResponse, EventStreamJsonMessage, IoTCoreMessage> {
  protected GeneratedAbstractSubscribeToIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToIoTCoreRequest, SubscribeToIoTCoreResponse, EventStreamJsonMessage, IoTCoreMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToIoTCoreModelContext();
  }
}
