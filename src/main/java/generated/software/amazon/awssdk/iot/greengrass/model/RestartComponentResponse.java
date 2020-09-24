package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class RestartComponentResponse implements EventStreamableJsonMessage {
  public static final RestartComponentResponse VOID;

  static {
    VOID = new RestartComponentResponse() {
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
  private Optional<RequestStatus> restartStatus;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> message;

  public RestartComponentResponse() {
    this.restartStatus = Optional.empty();
    this.message = Optional.empty();
  }

  public RequestStatus getRestartStatus() {
    if (restartStatus.isPresent()) {
      return restartStatus.get();
    }
    return null;
  }

  public void setRestartStatus(final RequestStatus restartStatus) {
    this.restartStatus = Optional.of(restartStatus);
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
    return "aws.greengrass#RestartComponentResponse";
  }
}
