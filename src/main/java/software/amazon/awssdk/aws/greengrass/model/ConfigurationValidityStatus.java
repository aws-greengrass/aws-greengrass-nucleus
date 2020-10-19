package software.amazon.awssdk.aws.greengrass.model;

import com.google.gson.annotations.SerializedName;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.lang.Override;
import java.lang.String;


public enum ConfigurationValidityStatus implements EventStreamJsonMessage {
  @SerializedName("ACCEPTED")
  ACCEPTED("ACCEPTED"),

  @SerializedName("REJECTED")
  REJECTED("REJECTED");

  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#ConfigurationValidityStatus";

  String value;

  ConfigurationValidityStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  @Override
  public String getApplicationModelType() {
    return APPLICATION_MODEL_TYPE;
  }
}
