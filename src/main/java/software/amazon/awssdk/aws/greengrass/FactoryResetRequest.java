package software.amazon.awssdk.aws.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Boolean;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public class FactoryResetRequest implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#FactoryResetRequest";

  public static final FactoryResetRequest VOID;

  static {
    VOID = new FactoryResetRequest() {
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
  private Optional<Boolean> cleanCloud;

  public FactoryResetRequest() {
    this.cleanCloud = Optional.empty();
  }

  /**
   * (Optional) If true (default), attempt to call greengrassv2:DeleteCoreDevice to remove cloud-side
   * deployment history before performing local cleanup. This requires the TES IAM role to have the
   * greengrass:DeleteCoreDevice permission. If the permission is missing the call is skipped and
   * local-only reset proceeds. If false, skip cloud cleanup entirely.
   */
  public Boolean isCleanCloud() {
    if (cleanCloud.isPresent()) {
      return cleanCloud.get();
    }
    return null;
  }

  /**
   * (Optional) If true (default), attempt to call greengrassv2:DeleteCoreDevice to remove cloud-side
   * deployment history before performing local cleanup. This requires the TES IAM role to have the
   * greengrass:DeleteCoreDevice permission. If the permission is missing the call is skipped and
   * local-only reset proceeds. If false, skip cloud cleanup entirely.
   */
  public void setCleanCloud(final Boolean cleanCloud) {
    this.cleanCloud = Optional.ofNullable(cleanCloud);
  }

  /**
   * (Optional) If true (default), attempt to call greengrassv2:DeleteCoreDevice to remove cloud-side
   * deployment history before performing local cleanup. This requires the TES IAM role to have the
   * greengrass:DeleteCoreDevice permission. If the permission is missing the call is skipped and
   * local-only reset proceeds. If false, skip cloud cleanup entirely.
   */
  public FactoryResetRequest withCleanCloud(final Boolean cleanCloud) {
    setCleanCloud(cleanCloud);
    return this;
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof FactoryResetRequest)) return false;
    if (this == rhs) return true;
    final FactoryResetRequest other = (FactoryResetRequest)rhs;
    boolean isEquals = true;
    isEquals = isEquals && this.cleanCloud.equals(other.cleanCloud);
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash(cleanCloud);
  }
}
