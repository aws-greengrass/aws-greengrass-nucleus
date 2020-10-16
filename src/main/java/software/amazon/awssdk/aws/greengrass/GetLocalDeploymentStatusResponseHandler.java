package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import java.lang.Void;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.aws.greengrass.model.GetLocalDeploymentStatusResponse;
import software.amazon.eventstream.iot.client.OperationResponse;
import software.amazon.eventstream.iot.client.StreamResponse;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public final class GetLocalDeploymentStatusResponseHandler implements StreamResponse<GetLocalDeploymentStatusResponse, EventStreamJsonMessage> {
  private final OperationResponse<GetLocalDeploymentStatusResponse, EventStreamJsonMessage> operationResponse;

  public GetLocalDeploymentStatusResponseHandler(
      final OperationResponse<GetLocalDeploymentStatusResponse, EventStreamJsonMessage> operationResponse) {
    this.operationResponse = operationResponse;
  }

  @Override
  public CompletableFuture<Void> getRequestFlushFuture() {
    return operationResponse.getRequestFlushFuture();
  }

  @Override
  public CompletableFuture<GetLocalDeploymentStatusResponse> getResponse() {
    return operationResponse.getResponse();
  }

  @Override
  public CompletableFuture<Void> sendStreamEvent(final EventStreamJsonMessage event) {
    return operationResponse.sendStreamEvent(event);
  }

  @Override
  public CompletableFuture<Void> closeStream() {
    return operationResponse.closeStream();
  }

  @Override
  public boolean isClosed() {
    return operationResponse.isClosed();
  }
}
