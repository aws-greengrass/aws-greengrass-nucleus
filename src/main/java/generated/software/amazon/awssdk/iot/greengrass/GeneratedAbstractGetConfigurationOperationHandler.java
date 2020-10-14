package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.GetConfigurationRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetConfigurationResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetConfigurationOperationHandler extends OperationContinuationHandler<GetConfigurationRequest, GetConfigurationResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetConfigurationOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetConfigurationRequest, GetConfigurationResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetConfigurationModelContext();
  }
}
