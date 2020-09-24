package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetLocalDeploymentStatusRequest implements EventStreamableJsonMessage {
  public static final GetLocalDeploymentStatusRequest VOID;

  static {
    VOID = new GetLocalDeploymentStatusRequest() {
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
  private Optional<String> deploymentId;

  public GetLocalDeploymentStatusRequest() {
    this.deploymentId = Optional.empty();
  }

  public String getDeploymentId() {
    if (deploymentId.isPresent()) {
      return deploymentId.get();
    }
    return null;
  }

  public void setDeploymentId(final String deploymentId) {
    this.deploymentId = Optional.of(deploymentId);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetLocalDeploymentStatusRequest";
  }
}
