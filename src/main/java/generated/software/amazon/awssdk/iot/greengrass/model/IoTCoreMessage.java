package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class IoTCoreMessage implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<MQTTMessage> message;

  public IoTCoreMessage() {
    this.message = Optional.empty();
  }

  public MQTTMessage getMessage() {
    if (message.isPresent() && (setUnionMember == UnionMember.MESSAGE)) {
      return message.get();
    }
    return null;
  }

  public void setMessage(final MQTTMessage message) {
    this.message = Optional.of(message);
    this.setUnionMember = UnionMember.MESSAGE;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#IoTCoreMessage";
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
    MESSAGE("MESSAGE", (generated.software.amazon.awssdk.iot.greengrass.model.IoTCoreMessage obj) -> obj.message = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.IoTCoreMessage obj) -> obj.message != null && !obj.message.isPresent());

    private String fieldName;

    private Consumer<IoTCoreMessage> nullifier;

    private Predicate<IoTCoreMessage> isPresent;

    UnionMember(String fieldName, Consumer<IoTCoreMessage> nullifier,
        Predicate<IoTCoreMessage> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(IoTCoreMessage obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(IoTCoreMessage obj) {
      return isPresent.test(obj);
    }
  }
}
