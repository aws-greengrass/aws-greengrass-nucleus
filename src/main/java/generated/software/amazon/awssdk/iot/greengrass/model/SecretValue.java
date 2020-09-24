package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SecretValue implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> secretString;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<byte[]> secretBinary;

  public SecretValue() {
    this.secretString = Optional.empty();
    this.secretBinary = Optional.empty();
  }

  public String getSecretString() {
    if (secretString.isPresent() && (setUnionMember == UnionMember.SECRET_STRING)) {
      return secretString.get();
    }
    return null;
  }

  public void setSecretString(final String secretString) {
    this.secretString = Optional.of(secretString);
    this.setUnionMember = UnionMember.SECRET_STRING;
  }

  public byte[] getSecretBinary() {
    if (secretBinary.isPresent() && (setUnionMember == UnionMember.SECRET_BINARY)) {
      return secretBinary.get();
    }
    return null;
  }

  public void setSecretBinary(final byte[] secretBinary) {
    this.secretBinary = Optional.of(secretBinary);
    this.setUnionMember = UnionMember.SECRET_BINARY;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SecretValue";
  }

  public void selfDesignateSetUnionMember() {
    int setCount = 0;
    UnionMember[] members = UnionMember.values();
    for (int memberIdx = 0; memberIdx < UnionMember.values().length; ++memberIdx) {
      if (members[memberIdx].isPresent(this)) {
        ++setCount;
        this.setUnionMember = members[memberIdx];
      }
    }
    // only bad outcome here is if there's more than one member set. It's possible for none to be set
    if (setCount > 1) {
      throw new IllegalArgumentException("More than one union member set for type: " + getApplicationModelType());
    }
  }

  public enum UnionMember {
    SECRET_STRING("SECRET_STRING", (generated.software.amazon.awssdk.iot.greengrass.model.SecretValue obj) -> obj.secretString = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.SecretValue obj) -> obj.secretString != null && !obj.secretString.isPresent()),

    SECRET_BINARY("SECRET_BINARY", (generated.software.amazon.awssdk.iot.greengrass.model.SecretValue obj) -> obj.secretBinary = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.SecretValue obj) -> obj.secretBinary != null && !obj.secretBinary.isPresent());

    private String fieldName;

    private Consumer<SecretValue> nullifier;

    private Predicate<SecretValue> isPresent;

    UnionMember(String fieldName, Consumer<SecretValue> nullifier,
        Predicate<SecretValue> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(SecretValue obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(SecretValue obj) {
      return isPresent.test(obj);
    }
  }
}
