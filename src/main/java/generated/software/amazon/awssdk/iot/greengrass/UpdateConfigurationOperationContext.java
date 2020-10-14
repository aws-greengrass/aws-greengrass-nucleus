package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class UpdateConfigurationOperationContext implements OperationModelContext<UpdateConfigurationRequest, UpdateConfigurationResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.UPDATE_CONFIGURATION;
  }

  @Override
  public Class<UpdateConfigurationRequest> getRequestTypeClass() {
    return UpdateConfigurationRequest.class;
  }

  @Override
  public Class<UpdateConfigurationResponse> getResponseTypeClass() {
    return UpdateConfigurationResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return UpdateConfigurationRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return UpdateConfigurationResponse.APPLICATION_MODEL_TYPE;
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
