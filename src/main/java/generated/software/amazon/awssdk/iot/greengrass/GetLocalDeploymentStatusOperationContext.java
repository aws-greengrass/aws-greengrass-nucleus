package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class GetLocalDeploymentStatusOperationContext implements OperationModelContext<GetLocalDeploymentStatusRequest, GetLocalDeploymentStatusResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.GET_LOCAL_DEPLOYMENT_STATUS;
  }

  @Override
  public Class<GetLocalDeploymentStatusRequest> getRequestTypeClass() {
    return GetLocalDeploymentStatusRequest.class;
  }

  @Override
  public Class<GetLocalDeploymentStatusResponse> getResponseTypeClass() {
    return GetLocalDeploymentStatusResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return GetLocalDeploymentStatusRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return GetLocalDeploymentStatusResponse.APPLICATION_MODEL_TYPE;
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
