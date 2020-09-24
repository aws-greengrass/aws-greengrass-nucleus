package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler extends OperationContinuationHandler<UpdateRecipesAndArtifactsRequest, UpdateRecipesAndArtifactsResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<UpdateRecipesAndArtifactsRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<UpdateRecipesAndArtifactsResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.UPDATE_RECIPES_AND_ARTIFACTS;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
