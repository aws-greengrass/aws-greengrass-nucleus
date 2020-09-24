package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class JsonMessage implements EventStreamableJsonMessage {
  public static final JsonMessage VOID;

  static {
    VOID = new JsonMessage() {
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
  private Optional<Map<String, Object>> message;

  public JsonMessage() {
    this.message = Optional.empty();
  }

  public Map<String, Object> getMessage() {
    if (message.isPresent()) {
      return message.get();
    }
    return null;
  }

  public void setMessage(final Map<String, Object> message) {
    this.message = Optional.ofNullable(message);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#JsonMessage";
  }
}
