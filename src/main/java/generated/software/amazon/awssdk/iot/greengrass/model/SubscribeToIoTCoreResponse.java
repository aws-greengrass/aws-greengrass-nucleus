package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToIoTCoreResponse implements EventStreamableJsonMessage {
  public static final SubscribeToIoTCoreResponse VOID;

  static {
    VOID = new SubscribeToIoTCoreResponse() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  public SubscribeToIoTCoreResponse() {
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToIoTCoreResponse";
  }
}
