package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class ValidateAuthorizationTokenOperationContext implements OperationModelContext<ValidateAuthorizationTokenRequest, ValidateAuthorizationTokenResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.VALIDATE_AUTHORIZATION_TOKEN;
  }

  @Override
  public Class<ValidateAuthorizationTokenRequest> getRequestTypeClass() {
    return ValidateAuthorizationTokenRequest.class;
  }

  @Override
  public Class<ValidateAuthorizationTokenResponse> getResponseTypeClass() {
    return ValidateAuthorizationTokenResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return ValidateAuthorizationTokenRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return ValidateAuthorizationTokenResponse.APPLICATION_MODEL_TYPE;
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
