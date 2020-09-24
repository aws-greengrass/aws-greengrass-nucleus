package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetConfigurationResponse implements EventStreamableJsonMessage {
  public static final GetConfigurationResponse VOID;

  static {
    VOID = new GetConfigurationResponse() {
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
  private Optional<Map<String, Object>> value;

  public GetConfigurationResponse() {
    this.componentName = Optional.empty();
    this.value = Optional.empty();
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

  public Map<String, Object> getValue() {
    if (value.isPresent()) {
      return value.get();
    }
    return null;
  }

  public void setValue(final Map<String, Object> value) {
    this.value = Optional.ofNullable(value);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetConfigurationResponse";
  }
}
