package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public enum ConfigurationValidityStatus implements EventStreamJsonMessage {
  ACCEPTED("ACCEPTED"),

  REJECTED("REJECTED");

  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#ConfigurationValidityStatus";

  String value;

  ConfigurationValidityStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return "value";
  }

  @Override
  public String getApplicationModelType() {
    return "APPLICATION_MODEL_TYPE";
  }
}
