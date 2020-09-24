package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToConfigurationUpdateRequest implements EventStreamableJsonMessage {
  public static final SubscribeToConfigurationUpdateRequest VOID;

  static {
    VOID = new SubscribeToConfigurationUpdateRequest() {
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

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<List<String>> keyPath;

  public SubscribeToConfigurationUpdateRequest() {
    this.componentName = Optional.empty();
    this.keyPath = Optional.empty();
  }

  public String getComponentName() {
    if (componentName.isPresent()) {
      return componentName.get();
    }
    return null;
  }

  public void setComponentName(final String componentName) {
    this.componentName = Optional.ofNullable(componentName);
  }

  public List<String> getKeyPath() {
    if (keyPath.isPresent()) {
      return keyPath.get();
    }
    return null;
  }

  public void setKeyPath(final List<String> keyPath) {
    this.keyPath = Optional.of(keyPath);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToConfigurationUpdateRequest";
  }
}
