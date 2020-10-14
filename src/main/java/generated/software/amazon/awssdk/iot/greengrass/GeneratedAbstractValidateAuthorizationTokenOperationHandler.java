package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractValidateAuthorizationTokenOperationHandler extends OperationContinuationHandler<ValidateAuthorizationTokenRequest, ValidateAuthorizationTokenResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractValidateAuthorizationTokenOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ValidateAuthorizationTokenRequest, ValidateAuthorizationTokenResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getValidateAuthorizationTokenModelContext();
  }
}
