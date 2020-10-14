package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class PublishToTopicOperationContext implements OperationModelContext<PublishToTopicRequest, PublishToTopicResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.PUBLISH_TO_TOPIC;
  }

  @Override
  public Class<PublishToTopicRequest> getRequestTypeClass() {
    return PublishToTopicRequest.class;
  }

  @Override
  public Class<PublishToTopicResponse> getResponseTypeClass() {
    return PublishToTopicResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return PublishToTopicRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return PublishToTopicResponse.APPLICATION_MODEL_TYPE;
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
