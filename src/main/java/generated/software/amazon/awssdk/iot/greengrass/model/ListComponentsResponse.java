package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ListComponentsResponse implements EventStreamableJsonMessage {
  public static final ListComponentsResponse VOID;

  static {
    VOID = new ListComponentsResponse() {
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
  private Optional<List<ComponentDetails>> components;

  public ListComponentsResponse() {
    this.components = Optional.empty();
  }

  public List<ComponentDetails> getComponents() {
    if (components.isPresent()) {
      return components.get();
    }
    return null;
  }

  public void setComponents(final List<ComponentDetails> components) {
    this.components = Optional.ofNullable(components);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ListComponentsResponse";
  }
}
