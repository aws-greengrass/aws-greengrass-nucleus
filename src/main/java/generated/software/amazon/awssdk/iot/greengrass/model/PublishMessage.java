package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class PublishMessage implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<JsonMessage> jsonMessage;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<BinaryMessage> binaryMessage;

  public PublishMessage() {
    this.jsonMessage = Optional.empty();
    this.binaryMessage = Optional.empty();
  }

  public JsonMessage getJsonMessage() {
    if (jsonMessage.isPresent() && (setUnionMember == UnionMember.JSON_MESSAGE)) {
      return jsonMessage.get();
    }
    return null;
  }

  public void setJsonMessage(final JsonMessage jsonMessage) {
    this.jsonMessage = Optional.of(jsonMessage);
    this.setUnionMember = UnionMember.JSON_MESSAGE;
  }

  public BinaryMessage getBinaryMessage() {
    if (binaryMessage.isPresent() && (setUnionMember == UnionMember.BINARY_MESSAGE)) {
      return binaryMessage.get();
    }
    return null;
  }

  public void setBinaryMessage(final BinaryMessage binaryMessage) {
    this.binaryMessage = Optional.of(binaryMessage);
    this.setUnionMember = UnionMember.BINARY_MESSAGE;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#PublishMessage";
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
    JSON_MESSAGE("JSON_MESSAGE", (generated.software.amazon.awssdk.iot.greengrass.model.PublishMessage obj) -> obj.jsonMessage = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.PublishMessage obj) -> obj.jsonMessage != null && !obj.jsonMessage.isPresent()),

    BINARY_MESSAGE("BINARY_MESSAGE", (generated.software.amazon.awssdk.iot.greengrass.model.PublishMessage obj) -> obj.binaryMessage = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.PublishMessage obj) -> obj.binaryMessage != null && !obj.binaryMessage.isPresent());

    private String fieldName;

    private Consumer<PublishMessage> nullifier;

    private Predicate<PublishMessage> isPresent;

    UnionMember(String fieldName, Consumer<PublishMessage> nullifier,
        Predicate<PublishMessage> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(PublishMessage obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(PublishMessage obj) {
      return isPresent.test(obj);
    }
  }
}
