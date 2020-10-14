package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public enum QOS implements EventStreamJsonMessage {
  AT_MOST_ONCE("0"),

  AT_LEAST_ONCE("1");

  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#QOS";

  String value;

  QOS(String value) {
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
