package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractListLocalDeploymentsOperationHandler extends OperationContinuationHandler<ListLocalDeploymentsRequest, ListLocalDeploymentsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractListLocalDeploymentsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ListLocalDeploymentsRequest, ListLocalDeploymentsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getListLocalDeploymentsModelContext();
  }
}
