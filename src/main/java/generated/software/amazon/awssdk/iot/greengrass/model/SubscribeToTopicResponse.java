package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToTopicResponse implements EventStreamableJsonMessage {
  public static final SubscribeToTopicResponse VOID;

  static {
    VOID = new SubscribeToTopicResponse() {
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
  private Optional<String> topicName;

  public SubscribeToTopicResponse() {
    this.topicName = Optional.empty();
  }

  public String getTopicName() {
    if (topicName.isPresent()) {
      return topicName.get();
    }
    return null;
  }

  public void setTopicName(final String topicName) {
    this.topicName = Optional.ofNullable(topicName);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToTopicResponse";
  }
}
