package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class InvalidArtifactsDirectoryPathError extends GreengrassCoreIPCError implements EventStreamableJsonMessage {
  public static final InvalidArtifactsDirectoryPathError VOID;

  static {
    VOID = new InvalidArtifactsDirectoryPathError() {
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

  public InvalidArtifactsDirectoryPathError(String errorMessage) {
    super("InvalidArtifactsDirectoryPathError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public InvalidArtifactsDirectoryPathError() {
    super("InvalidArtifactsDirectoryPathError", "");
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
    return "aws.greengrass#InvalidArtifactsDirectoryPathError";
  }
}
