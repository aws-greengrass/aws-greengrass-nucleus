package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ResourceNotFoundError extends GreengrassCoreIPCError implements EventStreamableJsonMessage {
  public static final ResourceNotFoundError VOID;

  static {
    VOID = new ResourceNotFoundError() {
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

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> resourceType;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> resourceName;

  public ResourceNotFoundError(String errorMessage) {
    super("ResourceNotFoundError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public ResourceNotFoundError() {
    super("ResourceNotFoundError", "");
    this.message = Optional.empty();
    this.resourceType = Optional.empty();
    this.resourceName = Optional.empty();
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

  public String getResourceType() {
    if (resourceType.isPresent()) {
      return resourceType.get();
    }
    return null;
  }

  public void setResourceType(final String resourceType) {
    this.resourceType = Optional.ofNullable(resourceType);
  }

  public String getResourceName() {
    if (resourceName.isPresent()) {
      return resourceName.get();
    }
    return null;
  }

  public void setResourceName(final String resourceName) {
    this.resourceName = Optional.ofNullable(resourceName);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ResourceNotFoundError";
  }
}
