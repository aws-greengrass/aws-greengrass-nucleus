package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class BinaryMessage implements EventStreamableJsonMessage {
  public static final BinaryMessage VOID;

  static {
    VOID = new BinaryMessage() {
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
  private Optional<byte[]> message;

  public BinaryMessage() {
    this.message = Optional.empty();
  }

  public byte[] getMessage() {
    if (message.isPresent()) {
      return message.get();
    }
    return null;
  }

  public void setMessage(final byte[] message) {
    this.message = Optional.ofNullable(message);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#BinaryMessage";
  }
}
