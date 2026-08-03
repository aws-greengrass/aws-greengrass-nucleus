package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetRequest;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractFactoryResetOperationHandler extends OperationContinuationHandler<FactoryResetRequest, FactoryResetResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractFactoryResetOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<FactoryResetRequest, FactoryResetResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getFactoryResetModelContext();
  }
}
