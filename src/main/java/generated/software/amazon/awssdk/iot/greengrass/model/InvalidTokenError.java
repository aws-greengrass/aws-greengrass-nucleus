package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class InvalidTokenError extends GreengrassCoreIPCError implements EventStreamableJsonMessage {
  public static final InvalidTokenError VOID;

  static {
    VOID = new InvalidTokenError() {
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
  private Optional<String> message;

  public InvalidTokenError(String errorMessage) {
    super("InvalidTokenError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public InvalidTokenError() {
    super("InvalidTokenError", "");
    this.message = Optional.empty();
  }

  @Override
  public String getErrorTypeString() {
    return "server";
  }

  public String getMessage() {
    if (message.isPresent()) {
      return message.get();
    }
    return null;
  }

  public void setMessage(final String message) {
    this.message = Optional.ofNullable(message);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#InvalidTokenError";
  }
}
