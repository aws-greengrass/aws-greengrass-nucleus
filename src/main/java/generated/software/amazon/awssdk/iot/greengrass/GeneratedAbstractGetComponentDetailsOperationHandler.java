package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetComponentDetailsOperationHandler extends OperationContinuationHandler<GetComponentDetailsRequest, GetComponentDetailsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetComponentDetailsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetComponentDetailsRequest, GetComponentDetailsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetComponentDetailsModelContext();
  }
}
