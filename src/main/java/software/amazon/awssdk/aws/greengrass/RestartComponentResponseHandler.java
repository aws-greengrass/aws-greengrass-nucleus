package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import java.lang.Void;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.aws.greengrass.model.RestartComponentResponse;
import software.amazon.awssdk.eventstreamrpc.OperationResponse;
import software.amazon.awssdk.eventstreamrpc.StreamResponse;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;


public final class RestartComponentResponseHandler implements StreamResponse<RestartComponentResponse, EventStreamJsonMessage> {
  private final OperationResponse<RestartComponentResponse, EventStreamJsonMessage> operationResponse;

  public RestartComponentResponseHandler(
      final OperationResponse<RestartComponentResponse, EventStreamJsonMessage> operationResponse) {
    this.operationResponse = operationResponse;
  }

  @Override
  public CompletableFuture<Void> getRequestFlushFuture() {
    return operationResponse.getRequestFlushFuture();
  }

  @Override
  public CompletableFuture<RestartComponentResponse> getResponse() {
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
