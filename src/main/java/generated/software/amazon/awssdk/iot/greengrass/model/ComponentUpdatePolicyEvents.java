package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class ComponentUpdatePolicyEvents implements EventStreamableJsonMessage {
  private transient UnionMember setUnionMember;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<PreComponentUpdateEvent> preUpdateEvent;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<PostComponentUpdateEvent> postUpdateEvent;

  public ComponentUpdatePolicyEvents() {
    this.preUpdateEvent = Optional.empty();
    this.postUpdateEvent = Optional.empty();
  }

  public PreComponentUpdateEvent getPreUpdateEvent() {
    if (preUpdateEvent.isPresent() && (setUnionMember == UnionMember.PRE_UPDATE_EVENT)) {
      return preUpdateEvent.get();
    }
    return null;
  }

  public void setPreUpdateEvent(final PreComponentUpdateEvent preUpdateEvent) {
    this.preUpdateEvent = Optional.of(preUpdateEvent);
    this.setUnionMember = UnionMember.PRE_UPDATE_EVENT;
  }

  public PostComponentUpdateEvent getPostUpdateEvent() {
    if (postUpdateEvent.isPresent() && (setUnionMember == UnionMember.POST_UPDATE_EVENT)) {
      return postUpdateEvent.get();
    }
    return null;
  }

  public void setPostUpdateEvent(final PostComponentUpdateEvent postUpdateEvent) {
    this.postUpdateEvent = Optional.of(postUpdateEvent);
    this.setUnionMember = UnionMember.POST_UPDATE_EVENT;
  }

  /**
   * Returns an indicator for which enum member is set. Can be used to convert to proper type.
   */
  public UnionMember getSetUnionMember() {
    return setUnionMember;
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#ComponentUpdatePolicyEvents";
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
    PRE_UPDATE_EVENT("PRE_UPDATE_EVENT", (generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents obj) -> obj.preUpdateEvent = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents obj) -> obj.preUpdateEvent != null && !obj.preUpdateEvent.isPresent()),

    POST_UPDATE_EVENT("POST_UPDATE_EVENT", (generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents obj) -> obj.postUpdateEvent = Optional.empty(), (generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents obj) -> obj.postUpdateEvent != null && !obj.postUpdateEvent.isPresent());

    private String fieldName;

    private Consumer<ComponentUpdatePolicyEvents> nullifier;

    private Predicate<ComponentUpdatePolicyEvents> isPresent;

    UnionMember(String fieldName, Consumer<ComponentUpdatePolicyEvents> nullifier,
        Predicate<ComponentUpdatePolicyEvents> isPresent) {
      this.fieldName = fieldName;
      this.nullifier = nullifier;
      this.isPresent = isPresent;
    }

    void nullify(ComponentUpdatePolicyEvents obj) {
      nullifier.accept(obj);
    }

    boolean isPresent(ComponentUpdatePolicyEvents obj) {
      return isPresent.test(obj);
    }
  }
}
