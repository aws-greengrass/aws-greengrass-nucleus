package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Long;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class DeferComponentUpdateRequest implements EventStreamableJsonMessage {
  public static final DeferComponentUpdateRequest VOID;

  static {
    VOID = new DeferComponentUpdateRequest() {
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

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<Long> recheckAfterMs;

  public DeferComponentUpdateRequest() {
    this.message = Optional.empty();
    this.recheckAfterMs = Optional.empty();
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

  public Long getRecheckAfterMs() {
    if (recheckAfterMs.isPresent()) {
      return recheckAfterMs.get();
    }
    return null;
  }

  public void setRecheckAfterMs(final Long recheckAfterMs) {
    this.recheckAfterMs = Optional.ofNullable(recheckAfterMs);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#DeferComponentUpdateRequest";
  }
}
