package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.IoTCoreMessage;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class SubscribeToIoTCoreOperationContext implements OperationModelContext<SubscribeToIoTCoreRequest, SubscribeToIoTCoreResponse, EventStreamJsonMessage, IoTCoreMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.SUBSCRIBE_TO_IOT_CORE;
  }

  @Override
  public Class<SubscribeToIoTCoreRequest> getRequestTypeClass() {
    return SubscribeToIoTCoreRequest.class;
  }

  @Override
  public Class<SubscribeToIoTCoreResponse> getResponseTypeClass() {
    return SubscribeToIoTCoreResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return SubscribeToIoTCoreRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return SubscribeToIoTCoreResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<IoTCoreMessage>> getStreamingResponseTypeClass() {
    return Optional.of(IoTCoreMessage.class);
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.of(IoTCoreMessage.APPLICATION_MODEL_TYPE);
  }
}
