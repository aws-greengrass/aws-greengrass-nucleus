package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToComponentUpdatesResponse implements EventStreamableJsonMessage {
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
    return "aws.greengrass#SubscribeToComponentUpdatesResponse";
  }
}
