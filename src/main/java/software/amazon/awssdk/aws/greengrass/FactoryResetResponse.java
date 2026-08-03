package software.amazon.awssdk.aws.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public class FactoryResetResponse implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#FactoryResetResponse";

  public static final FactoryResetResponse VOID;

  static {
    VOID = new FactoryResetResponse() {
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
  private Optional<String> status;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> message;

  public FactoryResetResponse() {
    this.status = Optional.empty();
    this.message = Optional.empty();
  }

  /**
   * The status of the factory reset. Will always be "INITIATED" because the device restarts
   * as part of the operation and the caller's connection will be dropped before completion.
   */
  public String getStatus() {
    if (status.isPresent()) {
      return status.get();
    }
    return null;
  }

  /**
   * The status of the factory reset. Will always be "INITIATED" because the device restarts
   * as part of the operation and the caller's connection will be dropped before completion.
   */
  public void setStatus(final String status) {
    this.status = Optional.ofNullable(status);
  }

  /**
   * The status of the factory reset. Will always be "INITIATED" because the device restarts
   * as part of the operation and the caller's connection will be dropped before completion.
   */
  public FactoryResetResponse withStatus(final String status) {
    setStatus(status);
    return this;
  }

  /**
   * A human-readable message describing the reset outcome.
   */
  public String getMessage() {
    if (message.isPresent()) {
      return message.get();
    }
    return null;
  }

  /**
   * A human-readable message describing the reset outcome.
   */
  public void setMessage(final String message) {
    this.message = Optional.ofNullable(message);
  }

  /**
   * A human-readable message describing the reset outcome.
   */
  public FactoryResetResponse withMessage(final String message) {
    setMessage(message);
    return this;
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof FactoryResetResponse)) return false;
    if (this == rhs) return true;
    final FactoryResetResponse other = (FactoryResetResponse)rhs;
    boolean isEquals = true;
    isEquals = isEquals && this.status.equals(other.status);
    isEquals = isEquals && this.message.equals(other.message);
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, message);
  }
}
