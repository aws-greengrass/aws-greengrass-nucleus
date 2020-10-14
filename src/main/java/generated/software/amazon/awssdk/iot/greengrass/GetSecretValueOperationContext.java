package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class GetSecretValueOperationContext implements OperationModelContext<GetSecretValueRequest, GetSecretValueResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.GET_SECRET_VALUE;
  }

  @Override
  public Class<GetSecretValueRequest> getRequestTypeClass() {
    return GetSecretValueRequest.class;
  }

  @Override
  public Class<GetSecretValueResponse> getResponseTypeClass() {
    return GetSecretValueResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return GetSecretValueRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return GetSecretValueResponse.APPLICATION_MODEL_TYPE;
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
