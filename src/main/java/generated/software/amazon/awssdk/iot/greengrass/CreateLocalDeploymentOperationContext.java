package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class CreateLocalDeploymentOperationContext implements OperationModelContext<CreateLocalDeploymentRequest, CreateLocalDeploymentResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.CREATE_LOCAL_DEPLOYMENT;
  }

  @Override
  public Class<CreateLocalDeploymentRequest> getRequestTypeClass() {
    return CreateLocalDeploymentRequest.class;
  }

  @Override
  public Class<CreateLocalDeploymentResponse> getResponseTypeClass() {
    return CreateLocalDeploymentResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return CreateLocalDeploymentRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return CreateLocalDeploymentResponse.APPLICATION_MODEL_TYPE;
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
