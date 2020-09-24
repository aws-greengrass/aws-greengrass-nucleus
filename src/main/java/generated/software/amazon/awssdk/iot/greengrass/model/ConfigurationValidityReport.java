package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ConfigurationValidityReport implements EventStreamableJsonMessage {
  public static final ConfigurationValidityReport VOID;

  static {
    VOID = new ConfigurationValidityReport() {
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
  private Optional<ConfigurationValidityStatus> status;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> message;

  public ConfigurationValidityReport() {
    this.status = Optional.empty();
    this.message = Optional.empty();
  }

  public ConfigurationValidityStatus getStatus() {
    if (status.isPresent()) {
      return status.get();
    }
    return null;
  }

  public void setStatus(final ConfigurationValidityStatus status) {
    this.status = Optional.of(status);
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
    return "aws.greengrass#ConfigurationValidityReport";
  }
}
