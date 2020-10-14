package software.amazon.eventstream.iot.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.eventstream.MessageFlags;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuationHandler;
import software.amazon.awssdk.crt.eventstream.ServerConnectionHandler;

public class ServiceOperationMappingContinuationHandler extends ServerConnectionHandler {
    private static final Logger LOGGER = Logger.getLogger(ServiceOperationMappingContinuationHandler.class.getName());
    private final EventStreamRPCServiceHandler serviceHandler;
    private AuthenticationData authenticationData;  //should only be set once after AuthN

    public ServiceOperationMappingContinuationHandler(final ServerConnection serverConnection, final EventStreamRPCServiceHandler handler) {
        super(serverConnection);
        this.serviceHandler = handler;
        this.authenticationData = null;
    }

    @Override
    protected void onProtocolMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {
        if (messageType == MessageType.Ping) {
            int responseMessageFlag = 0;
            MessageType responseMessageType = MessageType.PingResponse;

            connection.sendProtocolMessage(null, null, responseMessageType, responseMessageFlag);
        } else if (messageType == MessageType.Connect) {
            onConnectRequest(headers, payload);
        } else if (messageType != MessageType.PingResponse) {
            int responseMessageFlag = 0;
            MessageType responseMessageType = MessageType.ServerError;

            String responsePayload =
                    "{ \"error\": \"Unrecognized Message Type\" }" +
                            "\"message\": \" message type value: " + messageType.getEnumValue() + " is not recognized as a valid request path.\" }";

            Header contentTypeHeader = Header.createHeader(":content-type", "application/json");
            List<Header> responseHeaders = new ArrayList<>();
            responseHeaders.add(contentTypeHeader);
            CompletableFuture<Void> voidCompletableFuture = connection.sendProtocolMessage(responseHeaders, responsePayload.getBytes(StandardCharsets.UTF_8), responseMessageType, responseMessageFlag);
            voidCompletableFuture.thenAccept(result -> {connection.closeConnection(0); this.close();});
        }
    }

    /**
     * Post: authenticationData should not be null
     * @param headers
     * @param payload
     */
    protected void onConnectRequest(List<Header> headers, byte[] payload) {
        final int[] responseMessageFlag = { 0 };
        MessageType acceptResponseType = MessageType.ConnectAck;

        final AuthenticationHandler authentication = serviceHandler.getAuthenticationHandler();
        final AuthorizationHandler authorization = serviceHandler.getAuthorizationHandler();

        try {
            if (authentication == null) {
                throw new IllegalStateException(String.format("%s has null authentication handler!"));
            }
            if (authorization == null) {
                throw new IllegalStateException(String.format("%s has null authorization handler!"));
            }

            LOGGER.finer(String.format("%s running authentication handler", serviceHandler.getServiceName()));
            authenticationData = authentication.apply(headers, payload);
            if (authenticationData == null) {
                throw new IllegalStateException(String.format("%s authentication handler returned null", serviceHandler.getServiceName()));
            }
            LOGGER.info(String.format("%s authenticated identity: %s", serviceHandler.getServiceName(), authenticationData.getIdentityLabel()));

            final Authorization authorizationDecision = authorization.apply(authenticationData);
            switch (authorizationDecision) {
                case ACCEPT:
                    LOGGER.info("Connection accepted for " + authenticationData.getIdentityLabel());
                    responseMessageFlag[0] = MessageFlags.ConnectionAccepted.getByteValue();
                    break;
                case REJECT:
                    LOGGER.info("Connection rejected for: " + authenticationData.getIdentityLabel());
                    break;
                default:
                    //got a big problem if this is the outcome. Someone forgot to update this switch-case
                    throw new RuntimeException("Unknown authorization decision for " + authenticationData.getIdentityLabel());
            }
        }
        catch (Exception e) {
            LOGGER.severe(String.format("%s occurred while attempting to authN/authZ connect: %s", e.getClass(), e.getMessage()));
        }
        finally {
            LOGGER.info("Sending connect response for " + authenticationData.getIdentityLabel());
            connection.sendProtocolMessage(null, null, acceptResponseType, responseMessageFlag[0])
                .whenComplete((res, ex) -> {
                    if (ex != null) {
                        LOGGER.severe(String.format("Sending connection response for %s threw exception (%s): %s",
                            authenticationData.getIdentityLabel(), ex.getClass().getCanonicalName(), ex.getMessage()));
                    }
                    else {
                        LOGGER.info("Successfully sent connection response for: " + authenticationData.getIdentityLabel());
                    }
                    if (responseMessageFlag[0] != MessageFlags.ConnectionAccepted.getByteValue()) {
                        LOGGER.info("Closing connection due to connection not being accepted...");
                        connection.closeConnection(0);  //TODO: presuming zero means no shutdown error
                    }
                });
        }
    }

    @Override
    protected ServerConnectionContinuationHandler onIncomingStream(ServerConnectionContinuation continuation, String operationName) {
        final OperationContinuationHandlerContext operationContext = new OperationContinuationHandlerContext(
                connection, continuation, authenticationData);
        //TODO: future maturity can use this to ask the service handler for AuthN per operation.
        //      boolean authorizeOperation(AuthenticationData, operationName)
        //      would be the additional method on EventStreamRPCServiceHandler

        final Function<OperationContinuationHandlerContext, ? extends ServerConnectionContinuationHandler> registeredOperationHandlerFn =
                serviceHandler.getOperationHandler(operationName);
        if (registeredOperationHandlerFn != null) {
            return registeredOperationHandlerFn.apply(operationContext);
        } else {
            return new ServerConnectionContinuationHandler(continuation) {
                @Override
                protected void onContinuationClosed() {
                    close();
                }

                @Override
                protected void onContinuationMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {
                    int responseMessageFlag = MessageFlags.TerminateStream.getByteValue();
                    MessageType responseMessageType = MessageType.ApplicationError;

                    String responsePayload =
                            "{ \"error\": \"Unsupported Operation\", " +
                                    "\"message\": \"" + operationName + " is an unsupported operation.\" }";

                    Header contentTypeHeader = Header.createHeader(":content-type", "application/json");
                    List<Header> responseHeaders = new ArrayList<>();
                    responseHeaders.add(contentTypeHeader);

                    continuation.sendMessage(responseHeaders, responsePayload.getBytes(StandardCharsets.UTF_8), responseMessageType, responseMessageFlag);
                }
            };
        }
    }
}
