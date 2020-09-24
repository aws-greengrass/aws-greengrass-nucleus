package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ValidateAuthorizationTokenRequest implements EventStreamableJsonMessage {
  public static final ValidateAuthorizationTokenRequest VOID;

  static {
    VOID = new ValidateAuthorizationTokenRequest() {
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
  private Optional<String> token;

  public ValidateAuthorizationTokenRequest() {
    this.token = Optional.empty();
  }

  public String getToken() {
    if (token.isPresent()) {
      return token.get();
    }
    return null;
  }

  public void setToken(final String token) {
    this.token = Optional.of(token);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ValidateAuthorizationTokenRequest";
  }
}
