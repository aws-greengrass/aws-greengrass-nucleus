package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ListLocalDeploymentResponse implements EventStreamableJsonMessage {
  public static final ListLocalDeploymentResponse VOID;

  static {
    VOID = new ListLocalDeploymentResponse() {
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
  private Optional<List<LocalDeployment>> localDeployments;

  public ListLocalDeploymentResponse() {
    this.localDeployments = Optional.empty();
  }

  public List<LocalDeployment> getLocalDeployments() {
    if (localDeployments.isPresent()) {
      return localDeployments.get();
    }
    return null;
  }

  public void setLocalDeployments(final List<LocalDeployment> localDeployments) {
    this.localDeployments = Optional.ofNullable(localDeployments);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ListLocalDeploymentResponse";
  }
}
