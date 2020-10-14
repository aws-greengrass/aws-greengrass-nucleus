package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractRestartComponentOperationHandler extends OperationContinuationHandler<RestartComponentRequest, RestartComponentResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractRestartComponentOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<RestartComponentRequest, RestartComponentResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getRestartComponentModelContext();
  }
}
