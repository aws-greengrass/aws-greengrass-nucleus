package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class LocalDeployment implements EventStreamableJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#LocalDeployment";

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
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof LocalDeployment)) return false;
    if (this == rhs) return true;
    final LocalDeployment other = (LocalDeployment)rhs;
    boolean isEquals = true;
    isEquals = isEquals && this.deploymentId.equals(other.deploymentId);
    isEquals = isEquals && this.status.equals(other.status);
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash(deploymentId, status);
  }
}
