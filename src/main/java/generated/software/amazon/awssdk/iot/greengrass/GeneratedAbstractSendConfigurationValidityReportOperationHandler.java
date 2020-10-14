package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSendConfigurationValidityReportOperationHandler extends OperationContinuationHandler<SendConfigurationValidityReportRequest, SendConfigurationValidityReportResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractSendConfigurationValidityReportOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SendConfigurationValidityReportRequest, SendConfigurationValidityReportResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSendConfigurationValidityReportModelContext();
  }
}
