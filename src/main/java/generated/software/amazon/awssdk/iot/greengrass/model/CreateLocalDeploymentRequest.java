package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class CreateLocalDeploymentRequest implements EventStreamableJsonMessage {
  public static final CreateLocalDeploymentRequest VOID;

  static {
    VOID = new CreateLocalDeploymentRequest() {
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
  private Optional<String> groupName;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<Map<String, String>> rootComponentVersionsToAdd;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<List<String>> rootComponentsToRemove;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<Map<String, Map<String, Object>>> componentToConfiguration;

  public CreateLocalDeploymentRequest() {
    this.groupName = Optional.empty();
    this.rootComponentVersionsToAdd = Optional.empty();
    this.rootComponentsToRemove = Optional.empty();
    this.componentToConfiguration = Optional.empty();
  }

  public String getGroupName() {
    if (groupName.isPresent()) {
      return groupName.get();
    }
    return null;
  }

  public void setGroupName(final String groupName) {
    this.groupName = Optional.ofNullable(groupName);
  }

  public Map<String, String> getRootComponentVersionsToAdd() {
    if (rootComponentVersionsToAdd.isPresent()) {
      return rootComponentVersionsToAdd.get();
    }
    return null;
  }

  public void setRootComponentVersionsToAdd(final Map<String, String> rootComponentVersionsToAdd) {
    this.rootComponentVersionsToAdd = Optional.ofNullable(rootComponentVersionsToAdd);
  }

  public List<String> getRootComponentsToRemove() {
    if (rootComponentsToRemove.isPresent()) {
      return rootComponentsToRemove.get();
    }
    return null;
  }

  public void setRootComponentsToRemove(final List<String> rootComponentsToRemove) {
    this.rootComponentsToRemove = Optional.ofNullable(rootComponentsToRemove);
  }

  public Map<String, Map<String, Object>> getComponentToConfiguration() {
    if (componentToConfiguration.isPresent()) {
      return componentToConfiguration.get();
    }
    return null;
  }

  public void setComponentToConfiguration(
      final Map<String, Map<String, Object>> componentToConfiguration) {
    this.componentToConfiguration = Optional.ofNullable(componentToConfiguration);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#CreateLocalDeploymentRequest";
  }
}
