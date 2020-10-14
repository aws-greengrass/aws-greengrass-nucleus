package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.model.EventStreamOperationError;

public abstract class GreengrassCoreIPCError extends EventStreamOperationError implements EventStreamJsonMessage {
  GreengrassCoreIPCError(String errorCode, String errorMessage) {
    super("aws.greengrass#GreengrassCoreIPC", errorCode, errorMessage);
  }

  public abstract String getErrorTypeString();

  public boolean isRetryable() {
    return getErrorTypeString().equals("server");
  }

  public boolean isServerError() {
    return getErrorTypeString().equals("server");
  }

  public boolean isClientError() {
    return getErrorTypeString().equals("client");
  }
}
