package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToConfigurationUpdateResponse implements EventStreamableJsonMessage {
  public static final SubscribeToConfigurationUpdateResponse VOID;

  static {
    VOID = new SubscribeToConfigurationUpdateResponse() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  public SubscribeToConfigurationUpdateResponse() {
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToConfigurationUpdateResponse";
  }
}
