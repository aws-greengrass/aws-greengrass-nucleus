package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetSecretValueOperationHandler extends OperationContinuationHandler<GetSecretValueRequest, GetSecretValueResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetSecretValueOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetSecretValueRequest, GetSecretValueResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetSecretValueModelContext();
  }
}
