package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class LocalDeployment implements EventStreamableJsonMessage {
  public static final LocalDeployment VOID;

  static {
    VOID = new LocalDeployment() {
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

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<DeploymentStatus> status;

  public LocalDeployment() {
    this.deploymentId = Optional.empty();
    this.status = Optional.empty();
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

  public DeploymentStatus getStatus() {
    if (status.isPresent()) {
      return status.get();
    }
    return null;
  }

  public void setStatus(final DeploymentStatus status) {
    this.status = Optional.of(status);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#LocalDeployment";
  }
}
