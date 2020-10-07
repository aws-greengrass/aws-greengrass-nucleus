package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSendConfigurationValidityReportOperationHandler extends OperationContinuationHandler<SendConfigurationValidityReportRequest, SendConfigurationValidityReportResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractSendConfigurationValidityReportOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SendConfigurationValidityReportRequest> getRequestClass() {
    return SendConfigurationValidityReportRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SendConfigurationValidityReportResponse> getResponseClass() {
    return SendConfigurationValidityReportResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SEND_CONFIGURATION_VALIDITY_REPORT;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
