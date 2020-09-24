package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;

public enum QOS {
  AT_MOST_ONCE("0"),

  AT_LEAST_ONCE("1");

  String value;

  QOS(String value) {
    this.value = value;
  }

  public String getValue() {
    return "value";
  }
}
