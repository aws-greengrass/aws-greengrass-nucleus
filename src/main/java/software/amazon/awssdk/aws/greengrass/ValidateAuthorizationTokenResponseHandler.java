package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import java.lang.Void;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenResponse;
import software.amazon.eventstream.iot.client.OperationResponse;
import software.amazon.eventstream.iot.client.StreamResponse;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public final class ValidateAuthorizationTokenResponseHandler implements StreamResponse<ValidateAuthorizationTokenResponse, EventStreamJsonMessage> {
  private final OperationResponse<ValidateAuthorizationTokenResponse, EventStreamJsonMessage> operationResponse;

  public ValidateAuthorizationTokenResponseHandler(
      final OperationResponse<ValidateAuthorizationTokenResponse, EventStreamJsonMessage> operationResponse) {
    this.operationResponse = operationResponse;
  }

  @Override
  public CompletableFuture<Void> getRequestFlushFuture() {
    return operationResponse.getRequestFlushFuture();
  }

  @Override
  public CompletableFuture<ValidateAuthorizationTokenResponse> getResponse() {
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
