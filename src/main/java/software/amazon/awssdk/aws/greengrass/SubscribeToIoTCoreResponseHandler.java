package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import java.lang.Void;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.eventstream.iot.client.OperationResponse;
import software.amazon.eventstream.iot.client.StreamResponse;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public final class SubscribeToIoTCoreResponseHandler implements StreamResponse<SubscribeToIoTCoreResponse, EventStreamJsonMessage> {
  private final OperationResponse<SubscribeToIoTCoreResponse, EventStreamJsonMessage> operationResponse;

  public SubscribeToIoTCoreResponseHandler(
      final OperationResponse<SubscribeToIoTCoreResponse, EventStreamJsonMessage> operationResponse) {
    this.operationResponse = operationResponse;
  }

  @Override
  public CompletableFuture<Void> getRequestFlushFuture() {
    return operationResponse.getRequestFlushFuture();
  }

  @Override
  public CompletableFuture<SubscribeToIoTCoreResponse> getResponse() {
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
