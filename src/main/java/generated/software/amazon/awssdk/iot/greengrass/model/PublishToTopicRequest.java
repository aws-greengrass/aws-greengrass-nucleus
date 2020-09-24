package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class PublishToTopicRequest implements EventStreamableJsonMessage {
  public static final PublishToTopicRequest VOID;

  static {
    VOID = new PublishToTopicRequest() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> topic;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<PublishMessage> publishMessage;

  public PublishToTopicRequest() {
    this.topic = Optional.empty();
    this.publishMessage = Optional.empty();
  }

  public String getTopic() {
    if (topic.isPresent()) {
      return topic.get();
    }
    return null;
  }

  public void setTopic(final String topic) {
    this.topic = Optional.of(topic);
  }

  public PublishMessage getPublishMessage() {
    if (publishMessage.isPresent()) {
      return publishMessage.get();
    }
    return null;
  }

  public void setPublishMessage(final PublishMessage publishMessage) {
    this.publishMessage = Optional.of(publishMessage);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#PublishToTopicRequest";
  }
}
