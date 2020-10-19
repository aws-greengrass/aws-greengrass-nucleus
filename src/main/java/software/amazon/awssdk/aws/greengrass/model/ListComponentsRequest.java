package software.amazon.awssdk.aws.greengrass.model;

import com.google.gson.Gson;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;


public final class ListComponentsRequest implements EventStreamJsonMessage {
  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#ListComponentsRequest";

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
    return Objects.hash(ListComponentsRequest.class);
  }

  @Override
  public boolean equals(Object rhs) {
    if (rhs == null) return false;
    return (rhs instanceof ListComponentsRequest);
  }
}
