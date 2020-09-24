package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetComponentDetailsResponse implements EventStreamableJsonMessage {
  public static final GetComponentDetailsResponse VOID;

  static {
    VOID = new GetComponentDetailsResponse() {
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
  private Optional<ComponentDetails> componentDetails;

  public GetComponentDetailsResponse() {
    this.componentDetails = Optional.empty();
  }

  public ComponentDetails getComponentDetails() {
    if (componentDetails.isPresent()) {
      return componentDetails.get();
    }
    return null;
  }

  public void setComponentDetails(final ComponentDetails componentDetails) {
    this.componentDetails = Optional.of(componentDetails);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetComponentDetailsResponse";
  }
}
