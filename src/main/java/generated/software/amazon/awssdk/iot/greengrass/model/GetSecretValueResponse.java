package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class GetSecretValueResponse implements EventStreamableJsonMessage {
  public static final GetSecretValueResponse VOID;

  static {
    VOID = new GetSecretValueResponse() {
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
  private Optional<List<String>> versionStage;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<SecretValue> secretValue;

  public GetSecretValueResponse() {
    this.secretId = Optional.empty();
    this.versionId = Optional.empty();
    this.versionStage = Optional.empty();
    this.secretValue = Optional.empty();
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
    this.versionId = Optional.of(versionId);
  }

  public List<String> getVersionStage() {
    if (versionStage.isPresent()) {
      return versionStage.get();
    }
    return null;
  }

  public void setVersionStage(final List<String> versionStage) {
    this.versionStage = Optional.of(versionStage);
  }

  public SecretValue getSecretValue() {
    if (secretValue.isPresent()) {
      return secretValue.get();
    }
    return null;
  }

  public void setSecretValue(final SecretValue secretValue) {
    this.secretValue = Optional.of(secretValue);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#GetSecretValueResponse";
  }
}
