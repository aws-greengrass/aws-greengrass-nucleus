package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public enum LifecycleState implements EventStreamJsonMessage {
  RUNNING("RUNNING"),

  ERRORED("ERRORED"),

  NEW("NEW"),

  FINISHED("FINISHED"),

  INSTALLED("INSTALLED");

  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#LifecycleState";

  String value;

  LifecycleState(String value) {
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
