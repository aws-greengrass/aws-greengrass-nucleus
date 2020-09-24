package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ConfigurationUpdateEvents implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<ConfigurationUpdateEvent> configurationUpdateEvent;

  public ConfigurationUpdateEvents() {
    this.configurationUpdateEvent = Optional.empty();
  }

  public ConfigurationUpdateEvent getConfigurationUpdateEvent() {
    if (configurationUpdateEvent.isPresent() && (setUnionMember == UnionMember.CONFIGURATION_UPDATE_EVENT)) {
      return configurationUpdateEvent.get();
    }
    return null;
  }

  public void setConfigurationUpdateEvent(final ConfigurationUpdateEvent configurationUpdateEvent) {
    this.configurationUpdateEvent = Optional.of(configurationUpdateEvent);
    this.setUnionMember = UnionMember.CONFIGURATION_UPDATE_EVENT;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ConfigurationUpdateEvents";
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
    CONFIGURATION_UPDATE_EVENT("CONFIGURATION_UPDATE_EVENT", (generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents obj) -> obj.configurationUpdateEvent = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents obj) -> obj.configurationUpdateEvent != null && !obj.configurationUpdateEvent.isPresent());

    private String fieldName;

    private Consumer<ConfigurationUpdateEvents> nullifier;

    private Predicate<ConfigurationUpdateEvents> isPresent;

    UnionMember(String fieldName, Consumer<ConfigurationUpdateEvents> nullifier,
        Predicate<ConfigurationUpdateEvents> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(ConfigurationUpdateEvents obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(ConfigurationUpdateEvents obj) {
      return isPresent.test(obj);
    }
  }
}
