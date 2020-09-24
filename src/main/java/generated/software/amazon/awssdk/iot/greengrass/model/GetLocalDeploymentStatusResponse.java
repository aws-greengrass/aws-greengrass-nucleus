package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetLocalDeploymentStatusResponse implements EventStreamableJsonMessage {
  public static final GetLocalDeploymentStatusResponse VOID;

  static {
    VOID = new GetLocalDeploymentStatusResponse() {
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
  private Optional<LocalDeployment> deployment;

  public GetLocalDeploymentStatusResponse() {
    this.deployment = Optional.empty();
  }

  public LocalDeployment getDeployment() {
    if (deployment.isPresent()) {
      return deployment.get();
    }
    return null;
  }

  public void setDeployment(final LocalDeployment deployment) {
    this.deployment = Optional.of(deployment);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetLocalDeploymentStatusResponse";
  }
}
