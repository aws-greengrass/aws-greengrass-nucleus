package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;

import generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler extends OperationContinuationHandler<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamJsonMessage, ConfigurationUpdateEvents> {
  protected GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamJsonMessage, ConfigurationUpdateEvents> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToConfigurationUpdateModelContext();
  }
}
