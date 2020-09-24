package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;

public enum ConfigurationValidityStatus {
  ACCEPTED("ACCEPTED"),

  REJECTED("REJECTED");

  String value;

  ConfigurationValidityStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return "value";
  }
}
