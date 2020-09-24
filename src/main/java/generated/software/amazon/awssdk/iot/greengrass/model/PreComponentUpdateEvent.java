package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Boolean;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class PreComponentUpdateEvent implements EventStreamableJsonMessage {
  public static final PreComponentUpdateEvent VOID;

  static {
    VOID = new PreComponentUpdateEvent() {
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
  private Optional<Boolean> isGgcRestarting;

  public PreComponentUpdateEvent() {
    this.isGgcRestarting = Optional.empty();
  }

  public Boolean isIsGgcRestarting() {
    if (isGgcRestarting.isPresent()) {
      return isGgcRestarting.get();
    }
    return null;
  }

  public void setIsGgcRestarting(final Boolean isGgcRestarting) {
    this.isGgcRestarting = Optional.of(isGgcRestarting);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#PreComponentUpdateEvent";
  }
}
