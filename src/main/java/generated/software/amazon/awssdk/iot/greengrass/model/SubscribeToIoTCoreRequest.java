package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToIoTCoreRequest implements EventStreamableJsonMessage {
  public static final SubscribeToIoTCoreRequest VOID;

  static {
    VOID = new SubscribeToIoTCoreRequest() {
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

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<QOS> qos;

  public SubscribeToIoTCoreRequest() {
    this.topicName = Optional.empty();
    this.qos = Optional.empty();
  }

  public String getTopicName() {
    if (topicName.isPresent()) {
      return topicName.get();
    }
    return null;
  }

  public void setTopicName(final String topicName) {
    this.topicName = Optional.of(topicName);
  }

  public QOS getQos() {
    if (qos.isPresent()) {
      return qos.get();
    }
    return null;
  }

  public void setQos(final QOS qos) {
    this.qos = Optional.of(qos);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToIoTCoreRequest";
  }
}
