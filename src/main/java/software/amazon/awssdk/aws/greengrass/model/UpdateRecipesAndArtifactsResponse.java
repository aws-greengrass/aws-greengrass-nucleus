package software.amazon.awssdk.aws.greengrass.model;

import com.google.gson.Gson;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public final class UpdateRecipesAndArtifactsResponse implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#UpdateRecipesAndArtifactsResponse";

  @Override
  public byte[] toPayload(final Gson gson) {
    return gson.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }

  @Override
  public boolean isVoid() {
    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hash(UpdateRecipesAndArtifactsResponse.class);
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    return (rhs instanceof UpdateRecipesAndArtifactsResponse);
  }
}
