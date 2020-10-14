package generated.software.amazon.awssdk.iot.greengrass.model;

import java.lang.Override;
import java.lang.String;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public enum DeploymentStatus implements EventStreamJsonMessage {
  QUEUED("QUEUED"),

  IN_PROGRESS("IN_PROGRESS"),

  SUCCEEDED("SUCCEEDED"),

  FAILED("FAILED");

  public static final String APPLICATION_MODEL_TYPE = "aws.greengrass#DeploymentStatus";

  String value;

  DeploymentStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  @Override
  public String getApplicationModelType() {
    return "APPLICATION_MODEL_TYPE";
  }
}
