package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class UnsubscribeFromIoTCoreOperationContext implements OperationModelContext<UnsubscribeFromIoTCoreRequest, UnsubscribeFromIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.UNSUBSCRIBE_FROM_IOT_CORE;
  }

  @Override
  public Class<UnsubscribeFromIoTCoreRequest> getRequestTypeClass() {
    return UnsubscribeFromIoTCoreRequest.class;
  }

  @Override
  public Class<UnsubscribeFromIoTCoreResponse> getResponseTypeClass() {
    return UnsubscribeFromIoTCoreResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return UnsubscribeFromIoTCoreRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return UnsubscribeFromIoTCoreResponse.APPLICATION_MODEL_TYPE;
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
