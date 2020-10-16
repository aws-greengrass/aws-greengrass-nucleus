package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenRequest;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenResponse;
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
