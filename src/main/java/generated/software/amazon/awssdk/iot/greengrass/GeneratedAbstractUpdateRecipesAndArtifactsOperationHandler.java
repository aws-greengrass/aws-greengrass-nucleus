package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler extends OperationContinuationHandler<UpdateRecipesAndArtifactsRequest, UpdateRecipesAndArtifactsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<UpdateRecipesAndArtifactsRequest, UpdateRecipesAndArtifactsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getUpdateRecipesAndArtifactsModelContext();
  }
}
