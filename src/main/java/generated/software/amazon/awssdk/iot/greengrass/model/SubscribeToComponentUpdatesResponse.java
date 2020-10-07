package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToComponentUpdatesResponse implements EventStreamableJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#SubscribeToComponentUpdatesResponse";

  public static final SubscribeToComponentUpdatesResponse VOID;

  static {
    VOID = new SubscribeToComponentUpdatesResponse() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  public SubscribeToComponentUpdatesResponse() {
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof SubscribeToComponentUpdatesResponse)) return false;
    if (this == rhs) return true;
    final SubscribeToComponentUpdatesResponse other = (SubscribeToComponentUpdatesResponse)rhs;
    boolean isEquals = true;
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash();
  }
}
