package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ValidateConfigurationUpdateEvents implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<ValidateConfigurationUpdateEvent> validateConfigurationUpdateEvent;

  public ValidateConfigurationUpdateEvents() {
    this.validateConfigurationUpdateEvent = Optional.empty();
  }

  public ValidateConfigurationUpdateEvent getValidateConfigurationUpdateEvent() {
    if (validateConfigurationUpdateEvent.isPresent() && (setUnionMember == UnionMember.VALIDATE_CONFIGURATION_UPDATE_EVENT)) {
      return validateConfigurationUpdateEvent.get();
    }
    return null;
  }

  public void setValidateConfigurationUpdateEvent(
      final ValidateConfigurationUpdateEvent validateConfigurationUpdateEvent) {
    this.validateConfigurationUpdateEvent = Optional.of(validateConfigurationUpdateEvent);
    this.setUnionMember = UnionMember.VALIDATE_CONFIGURATION_UPDATE_EVENT;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ValidateConfigurationUpdateEvents";
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
    VALIDATE_CONFIGURATION_UPDATE_EVENT("VALIDATE_CONFIGURATION_UPDATE_EVENT", (generated.software.amazon.awssdk.iot.greengrass.model.ValidateConfigurationUpdateEvents obj) -> obj.validateConfigurationUpdateEvent = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.ValidateConfigurationUpdateEvents obj) -> obj.validateConfigurationUpdateEvent != null && !obj.validateConfigurationUpdateEvent.isPresent());

    private String fieldName;

    private Consumer<ValidateConfigurationUpdateEvents> nullifier;

    private Predicate<ValidateConfigurationUpdateEvents> isPresent;

    UnionMember(String fieldName, Consumer<ValidateConfigurationUpdateEvents> nullifier,
        Predicate<ValidateConfigurationUpdateEvents> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(ValidateConfigurationUpdateEvents obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(ValidateConfigurationUpdateEvents obj) {
      return isPresent.test(obj);
    }
  }
}
