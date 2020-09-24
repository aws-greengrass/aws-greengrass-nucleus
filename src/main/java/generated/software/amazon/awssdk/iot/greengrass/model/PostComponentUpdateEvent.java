package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class PostComponentUpdateEvent implements EventStreamableJsonMessage {
  public static final PostComponentUpdateEvent VOID;

  static {
    VOID = new PostComponentUpdateEvent() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  public PostComponentUpdateEvent() {
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#PostComponentUpdateEvent";
  }
}
