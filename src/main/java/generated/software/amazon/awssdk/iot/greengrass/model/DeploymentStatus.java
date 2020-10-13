package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;

public enum DeploymentStatus {
  QUEUED("QUEUED"),

  IN_PROGRESS("IN_PROGRESS"),

  SUCCEEDED("SUCCEEDED"),

  FAILED("FAILED");

  String value;

  DeploymentStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
