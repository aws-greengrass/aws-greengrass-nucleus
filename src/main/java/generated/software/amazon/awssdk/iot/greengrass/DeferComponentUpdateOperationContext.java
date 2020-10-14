package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class DeferComponentUpdateOperationContext implements OperationModelContext<DeferComponentUpdateRequest, DeferComponentUpdateResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.DEFER_COMPONENT_UPDATE;
  }

  @Override
  public Class<DeferComponentUpdateRequest> getRequestTypeClass() {
    return DeferComponentUpdateRequest.class;
  }

  @Override
  public Class<DeferComponentUpdateResponse> getResponseTypeClass() {
    return DeferComponentUpdateResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return DeferComponentUpdateRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return DeferComponentUpdateResponse.APPLICATION_MODEL_TYPE;
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
