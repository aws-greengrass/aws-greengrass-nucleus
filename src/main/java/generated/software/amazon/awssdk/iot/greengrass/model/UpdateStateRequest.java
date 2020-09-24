package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class UpdateStateRequest implements EventStreamableJsonMessage {
  public static final UpdateStateRequest VOID;

  static {
    VOID = new UpdateStateRequest() {
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
  private Optional<LifecycleState> state;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> serviceName;

  public UpdateStateRequest() {
    this.state = Optional.empty();
    this.serviceName = Optional.empty();
  }

  public LifecycleState getState() {
    if (state.isPresent()) {
      return state.get();
    }
    return null;
  }

  public void setState(final LifecycleState state) {
    this.state = Optional.of(state);
  }

  public String getServiceName() {
    if (serviceName.isPresent()) {
      return serviceName.get();
    }
    return null;
  }

  public void setServiceName(final String serviceName) {
    this.serviceName = Optional.ofNullable(serviceName);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#UpdateStateRequest";
  }
}
