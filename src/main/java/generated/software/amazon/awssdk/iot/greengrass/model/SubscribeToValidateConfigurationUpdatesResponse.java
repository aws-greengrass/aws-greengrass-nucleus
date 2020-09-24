package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SubscribeToValidateConfigurationUpdatesResponse implements EventStreamableJsonMessage {
  public static final SubscribeToValidateConfigurationUpdatesResponse VOID;

  static {
    VOID = new SubscribeToValidateConfigurationUpdatesResponse() {
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
  private Optional<ValidateConfigurationUpdateEvents> messages;

  public SubscribeToValidateConfigurationUpdatesResponse() {
    this.messages = Optional.empty();
  }

  public ValidateConfigurationUpdateEvents getMessages() {
    if (messages.isPresent()) {
      return messages.get();
    }
    return null;
  }

  public void setMessages(final ValidateConfigurationUpdateEvents messages) {
    this.messages = Optional.ofNullable(messages);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SubscribeToValidateConfigurationUpdatesResponse";
  }
}
