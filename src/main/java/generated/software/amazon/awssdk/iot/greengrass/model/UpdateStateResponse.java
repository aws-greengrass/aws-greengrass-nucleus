package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.Gson;
import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

/**
 * Auto-generated empty model type
 */
public final class UpdateStateResponse implements EventStreamableJsonMessage {
  @Override
  public byte[] toPayload(final Gson gson) {
    return gson.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#UpdateStateResponse";
  }
}
