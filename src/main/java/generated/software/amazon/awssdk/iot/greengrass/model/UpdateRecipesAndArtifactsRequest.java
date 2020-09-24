package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class UpdateRecipesAndArtifactsRequest implements EventStreamableJsonMessage {
  public static final UpdateRecipesAndArtifactsRequest VOID;

  static {
    VOID = new UpdateRecipesAndArtifactsRequest() {
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
  private Optional<String> recipeDirectoryPath;

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<String> artifactsDirectoryPath;

  public UpdateRecipesAndArtifactsRequest() {
    this.recipeDirectoryPath = Optional.empty();
    this.artifactsDirectoryPath = Optional.empty();
  }

  public String getRecipeDirectoryPath() {
    if (recipeDirectoryPath.isPresent()) {
      return recipeDirectoryPath.get();
    }
    return null;
  }

  public void setRecipeDirectoryPath(final String recipeDirectoryPath) {
    this.recipeDirectoryPath = Optional.ofNullable(recipeDirectoryPath);
  }

  public String getArtifactsDirectoryPath() {
    if (artifactsDirectoryPath.isPresent()) {
      return artifactsDirectoryPath.get();
    }
    return null;
  }

  public void setArtifactsDirectoryPath(final String artifactsDirectoryPath) {
    this.artifactsDirectoryPath = Optional.ofNullable(artifactsDirectoryPath);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#UpdateRecipesAndArtifactsRequest";
  }
}
