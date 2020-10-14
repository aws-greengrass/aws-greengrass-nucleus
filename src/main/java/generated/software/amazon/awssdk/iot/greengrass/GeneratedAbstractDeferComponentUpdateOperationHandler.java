package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractDeferComponentUpdateOperationHandler extends OperationContinuationHandler<DeferComponentUpdateRequest, DeferComponentUpdateResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractDeferComponentUpdateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<DeferComponentUpdateRequest, DeferComponentUpdateResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getDeferComponentUpdateModelContext();
  }
}
