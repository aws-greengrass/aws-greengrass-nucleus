package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class RestartComponentRequest implements EventStreamableJsonMessage {
  public static final RestartComponentRequest VOID;

  static {
    VOID = new RestartComponentRequest() {
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
  private Optional<String> componentName;

  public RestartComponentRequest() {
    this.componentName = Optional.empty();
  }

  public String getComponentName() {
    if (componentName.isPresent()) {
      return componentName.get();
    }
    return null;
  }

  public void setComponentName(final String componentName) {
    this.componentName = Optional.of(componentName);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#RestartComponentRequest";
  }
}
