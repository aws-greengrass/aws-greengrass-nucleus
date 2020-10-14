package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SendConfigurationValidityReportResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class SendConfigurationValidityReportOperationContext implements OperationModelContext<SendConfigurationValidityReportRequest, SendConfigurationValidityReportResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.SEND_CONFIGURATION_VALIDITY_REPORT;
  }

  @Override
  public Class<SendConfigurationValidityReportRequest> getRequestTypeClass() {
    return SendConfigurationValidityReportRequest.class;
  }

  @Override
  public Class<SendConfigurationValidityReportResponse> getResponseTypeClass() {
    return SendConfigurationValidityReportResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return SendConfigurationValidityReportRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return SendConfigurationValidityReportResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingResponseTypeClass() {
    return Optional.empty();
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.empty();
  }
}
