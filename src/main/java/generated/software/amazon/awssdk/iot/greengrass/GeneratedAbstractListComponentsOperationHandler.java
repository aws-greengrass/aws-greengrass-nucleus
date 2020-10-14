package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractListComponentsOperationHandler extends OperationContinuationHandler<ListComponentsRequest, ListComponentsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractListComponentsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ListComponentsRequest, ListComponentsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getListComponentsModelContext();
  }
}
