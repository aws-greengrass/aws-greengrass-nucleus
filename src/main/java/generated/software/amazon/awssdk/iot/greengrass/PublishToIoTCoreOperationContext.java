package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class PublishToIoTCoreOperationContext implements OperationModelContext<PublishToIoTCoreRequest, PublishToIoTCoreResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.PUBLISH_TO_IOT_CORE;
  }

  @Override
  public Class<PublishToIoTCoreRequest> getRequestTypeClass() {
    return PublishToIoTCoreRequest.class;
  }

  @Override
  public Class<PublishToIoTCoreResponse> getResponseTypeClass() {
    return PublishToIoTCoreResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return PublishToIoTCoreRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return PublishToIoTCoreResponse.APPLICATION_MODEL_TYPE;
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
