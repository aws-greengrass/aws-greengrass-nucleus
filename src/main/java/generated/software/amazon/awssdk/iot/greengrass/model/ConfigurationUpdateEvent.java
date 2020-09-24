package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ConfigurationUpdateEvent implements EventStreamableJsonMessage {
  public static final ConfigurationUpdateEvent VOID;

  static {
    VOID = new ConfigurationUpdateEvent() {
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

  public ConfigurationUpdateEvent() {
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
    this.componentName = Optional.of(componentName);
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
    return "aws.greengrass#ConfigurationUpdateEvent";
  }
}
