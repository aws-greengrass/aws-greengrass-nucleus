package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToTopicRequest implements EventStreamableJsonMessage {
  public static final SubscribeToTopicRequest VOID;

  static {
    VOID = new SubscribeToTopicRequest() {
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
  private Optional<String> source;

  public SubscribeToTopicRequest() {
    this.topic = Optional.empty();
    this.source = Optional.empty();
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

  public String getSource() {
    if (source.isPresent()) {
      return source.get();
    }
    return null;
  }

  public void setSource(final String source) {
    this.source = Optional.ofNullable(source);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToTopicRequest";
  }
}
