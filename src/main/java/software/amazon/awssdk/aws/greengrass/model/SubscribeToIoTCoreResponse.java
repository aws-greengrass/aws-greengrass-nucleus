package software.amazon.awssdk.aws.greengrass.model;

import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;


public class SubscribeToIoTCoreResponse implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#SubscribeToIoTCoreResponse";

  public static final SubscribeToIoTCoreResponse VOID;

  static {
    VOID = new SubscribeToIoTCoreResponse() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  public SubscribeToIoTCoreResponse() {
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    if (!(rhs instanceof SubscribeToIoTCoreResponse)) return false;
    if (this == rhs) return true;
    final SubscribeToIoTCoreResponse other = (SubscribeToIoTCoreResponse)rhs;
    boolean isEquals = true;
    return isEquals;
  }

  @Override
  public int hashCode() {
    return Objects.hash();
  }
}
