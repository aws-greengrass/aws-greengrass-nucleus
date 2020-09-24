package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;
import software.amazon.eventstream.iot.EventStreamOperationError;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public abstract class GreengrassCoreIPCError extends EventStreamOperationError implements EventStreamableJsonMessage {
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
