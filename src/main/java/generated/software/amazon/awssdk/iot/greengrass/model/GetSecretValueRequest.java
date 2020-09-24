package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetSecretValueRequest implements EventStreamableJsonMessage {
  public static final GetSecretValueRequest VOID;

  static {
    VOID = new GetSecretValueRequest() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> secretId;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> versionId;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> versionStage;

  public GetSecretValueRequest() {
    this.secretId = Optional.empty();
    this.versionId = Optional.empty();
    this.versionStage = Optional.empty();
  }

  public String getSecretId() {
    if (secretId.isPresent()) {
      return secretId.get();
    }
    return null;
  }

  public void setSecretId(final String secretId) {
    this.secretId = Optional.of(secretId);
  }

  public String getVersionId() {
    if (versionId.isPresent()) {
      return versionId.get();
    }
    return null;
  }

  public void setVersionId(final String versionId) {
    this.versionId = Optional.ofNullable(versionId);
  }

  public String getVersionStage() {
    if (versionStage.isPresent()) {
      return versionStage.get();
    }
    return null;
  }

  public void setVersionStage(final String versionStage) {
    this.versionStage = Optional.ofNullable(versionStage);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetSecretValueRequest";
  }
}
