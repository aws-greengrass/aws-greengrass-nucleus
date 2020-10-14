package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class UpdateStateOperationContext implements OperationModelContext<UpdateStateRequest, UpdateStateResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.UPDATE_STATE;
  }

  @Override
  public Class<UpdateStateRequest> getRequestTypeClass() {
    return UpdateStateRequest.class;
  }

  @Override
  public Class<UpdateStateResponse> getResponseTypeClass() {
    return UpdateStateResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return UpdateStateRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return UpdateStateResponse.APPLICATION_MODEL_TYPE;
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
