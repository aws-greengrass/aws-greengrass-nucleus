package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class StopComponentResponse implements EventStreamableJsonMessage {
  public static final StopComponentResponse VOID;

  static {
    VOID = new StopComponentResponse() {
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
  private Optional<RequestStatus> stopStatus;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> message;

  public StopComponentResponse() {
    this.stopStatus = Optional.empty();
    this.message = Optional.empty();
  }

  public RequestStatus getStopStatus() {
    if (stopStatus.isPresent()) {
      return stopStatus.get();
    }
    return null;
  }

  public void setStopStatus(final RequestStatus stopStatus) {
    this.stopStatus = Optional.of(stopStatus);
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
    return "aws.greengrass#StopComponentResponse";
  }
}
