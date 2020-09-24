package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Boolean;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ValidateAuthorizationTokenResponse implements EventStreamableJsonMessage {
  public static final ValidateAuthorizationTokenResponse VOID;

  static {
    VOID = new ValidateAuthorizationTokenResponse() {
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
  private Optional<Boolean> isValid;

  public ValidateAuthorizationTokenResponse() {
    this.isValid = Optional.empty();
  }

  public Boolean isIsValid() {
    if (isValid.isPresent()) {
      return isValid.get();
    }
    return null;
  }

  public void setIsValid(final Boolean isValid) {
    this.isValid = Optional.of(isValid);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ValidateAuthorizationTokenResponse";
  }
}
