package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ComponentDetails implements EventStreamableJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#ComponentDetails";

  public static final ComponentDetails VOID;

  static {
    VOID = new ComponentDetails() {
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
  private Optional<String> version;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<LifecycleState> state;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<Map<String, Object>> configuration;

  public ComponentDetails() {
    this.componentName = Optional.empty();
    this.version = Optional.empty();
    this.state = Optional.empty();
    this.configuration = Optional.empty();
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

  public String getVersion() {
    if (version.isPresent()) {
      return version.get();
    }
    return null;
  }

  public void setVersion(final String version) {
    this.version = Optional.of(version);
  }

  public LifecycleState getState() {
    if (state.isPresent()) {
      return state.get();
    }
    return null;
  }

  public void setState(final LifecycleState state) {
    this.state = Optional.of(state);
  }

  public Map<String, Object> getConfiguration() {
    if (configuration.isPresent()) {
      return configuration.get();
    }
    return null;
  }

  public void setConfiguration(final Map<String, Object> configuration) {
    this.configuration = Optional.ofNullable(configuration);
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof ComponentDetails)) return false;
    if (this == rhs) return true;
    final ComponentDetails other = (ComponentDetails)rhs;
    boolean isEquals = true;
    isEquals = isEquals && this.componentName.equals(other.componentName);
    isEquals = isEquals && this.version.equals(other.version);
    isEquals = isEquals && this.state.equals(other.state);
    isEquals = isEquals && this.configuration.equals(other.configuration);
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash(componentName, version, state, configuration);
  }
}
