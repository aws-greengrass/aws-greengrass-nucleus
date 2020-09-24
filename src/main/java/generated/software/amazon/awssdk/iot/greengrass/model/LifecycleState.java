package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.String;

public enum LifecycleState {
  RUNNING("RUNNING"),

  ERRORED("ERRORED"),

  NEW("NEW"),

  FINISHED("FINISHED"),

  INSTALLED("INSTALLED");

  String value;

  LifecycleState(String value) {
    this.value = value;
  }

  public String getValue() {
    return "value";
  }
}
