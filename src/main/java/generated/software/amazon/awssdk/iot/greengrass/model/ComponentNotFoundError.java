package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ComponentNotFoundError extends GreengrassCoreIPCError implements EventStreamableJsonMessage {
  public static final ComponentNotFoundError VOID;

  static {
    VOID = new ComponentNotFoundError() {
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

  public ComponentNotFoundError(String errorMessage) {
    super("ComponentNotFoundError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public ComponentNotFoundError() {
    super("ComponentNotFoundError", "");
    this.message = Optional.empty();
  }

  @Override
  public String getErrorTypeString() {
    return "client";
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
    return "aws.greengrass#ComponentNotFoundError";
  }
}
