package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import java.lang.Void;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsResponse;
import software.amazon.eventstream.iot.client.OperationResponse;
import software.amazon.eventstream.iot.client.StreamResponse;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public final class GetComponentDetailsResponseHandler implements StreamResponse<GetComponentDetailsResponse, EventStreamJsonMessage> {
  private final OperationResponse<GetComponentDetailsResponse, EventStreamJsonMessage> operationResponse;

  public GetComponentDetailsResponseHandler(
      final OperationResponse<GetComponentDetailsResponse, EventStreamJsonMessage> operationResponse) {
    this.operationResponse = operationResponse;
  }

  @Override
  public CompletableFuture<Void> getRequestFlushFuture() {
    return operationResponse.getRequestFlushFuture();
  }

  @Override
  public CompletableFuture<GetComponentDetailsResponse> getResponse() {
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
