package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class InvalidRecipeDirectoryPathError extends GreengrassCoreIPCError implements EventStreamableJsonMessage {
  public static final InvalidRecipeDirectoryPathError VOID;

  static {
    VOID = new InvalidRecipeDirectoryPathError() {
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

  public InvalidRecipeDirectoryPathError(String errorMessage) {
    super("InvalidRecipeDirectoryPathError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public InvalidRecipeDirectoryPathError() {
    super("InvalidRecipeDirectoryPathError", "");
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
    return "aws.greengrass#InvalidRecipeDirectoryPathError";
  }
}
