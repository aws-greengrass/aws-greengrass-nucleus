package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import java.util.Optional;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class FailedUpdateConditionCheckError extends GreengrassCoreIPCError implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#FailedUpdateConditionCheckError";

  public static final FailedUpdateConditionCheckError VOID;

  static {
    VOID = new FailedUpdateConditionCheckError() {
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
  private Optional<String> message;

  public FailedUpdateConditionCheckError(String errorMessage) {
    super("FailedUpdateConditionCheckError", errorMessage);
    this.message = Optional.ofNullable(errorMessage);
  }

  public FailedUpdateConditionCheckError() {
    super("FailedUpdateConditionCheckError", "");
    this.message = Optional.empty();
  }

  @Override
  public String getErrorTypeString() {
    return "client";
  }

  public String getMessage() {
    if (message.isPresent()) {
      return message.get();
    }
    return null;
  }

  public void setMessage(final String message) {
    this.message = Optional.ofNullable(message);
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof FailedUpdateConditionCheckError)) return false;
    if (this == rhs) return true;
    final FailedUpdateConditionCheckError other = (FailedUpdateConditionCheckError)rhs;
    boolean isEquals = true;
    isEquals = isEquals && this.message.equals(other.message);
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash(message);
  }
}
