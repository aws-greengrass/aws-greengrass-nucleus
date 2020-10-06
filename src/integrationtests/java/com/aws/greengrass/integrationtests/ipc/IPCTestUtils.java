package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.common.GGEventStreamConnectMessage;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import lombok.NonNull;
import software.amazon.awssdk.crt.eventstream.ClientConnection;
import software.amazon.awssdk.crt.eventstream.ClientConnectionContinuation;
import software.amazon.awssdk.crt.eventstream.ClientConnectionContinuationHandler;
import software.amazon.awssdk.crt.eventstream.ClientConnectionHandler;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.eventstream.iot.EventStreamServiceModel;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.IPCEventStreamService.IPC_SERVER_DOMAIN_SOCKET_FILENAME;
import static com.aws.greengrass.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;


public final class IPCTestUtils {

    static Gson gson = EventStreamServiceModel.GSON;

    public static String TEST_SERVICE_NAME = "ServiceName";

    private static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static int STREAM_ID = 1;
    private IPCTestUtils() {

    }

    public static KernelIPCClientConfig getIPCConfigForService(String serviceName, Kernel kernel) throws ServiceLoadException, URISyntaxException {
        Topic kernelUri = kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME);
        URI serverUri = null;
        serverUri = new URI((String) kernelUri.getOnce());

        int port = serverUri.getPort();
        String address = serverUri.getHost();

        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token(Coerce.toString(kernel.locate(serviceName).getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                        .getOnce())).build();
    }

    public static Kernel prepareKernelFromConfigFile(String configFile, Class testClass, String... serviceNames) throws InterruptedException {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", testClass.getResource(configFile).toString());

        // ensure awaitIpcServiceLatch starts
        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(serviceNames.length);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (serviceNames != null && serviceNames.length != 0) {
                for (String serviceName:serviceNames) {
                    if (service.getName().equals(serviceName) && newState.equals(State.RUNNING)) {
                        awaitIpcServiceLatch.countDown();
                        break;
                    }
                }
            }
        });

        kernel.launch();
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        return kernel;
    }

    public static CountDownLatch waitForDeploymentToBeSuccessful(String deploymentId, Kernel kernel) {
        CountDownLatch deploymentLatch = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (deploymentDetails) ->
        {
            String receivedDeploymentId =
                    deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME).toString();
            if (receivedDeploymentId.equals(deploymentId)) {
                DeploymentStatus status = DeploymentStatus.valueOf(deploymentDetails
                        .get(DEPLOYMENT_STATUS_KEY_NAME).toString());
                if (status == DeploymentStatus.SUCCEEDED) {
                    deploymentLatch.countDown();
                }
            }
            return true;
        }, deploymentId);
        return deploymentLatch;
    }

    public static CountDownLatch waitForServiceToComeInState(String serviceName, State state, Kernel kernel) {
        // wait for service to come up
        CountDownLatch awaitServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(serviceName) && newState.equals(state)) {
                awaitServiceLatch.countDown();
            }
        });
        return awaitServiceLatch;
    }

    @SuppressWarnings("PMD.CloseResource") // the connect message is closed still PMD failure comes
    public static ClientConnection connectClientForEventStreamIpc(String authToken, Kernel kernel) throws IOException,
            InterruptedException,
            ExecutionException {
        final ClientConnection[] clientConnectionArray = {null};
        SocketOptions socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.LOCAL;
        socketOptions.type = SocketOptions.SocketType.STREAM;
        EventLoopGroup elg = new EventLoopGroup(1);
        ClientBootstrap clientBootstrap = new ClientBootstrap(elg, new HostResolver(elg));
        String ipcServerSocketPath = kernel.getRootPath().resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
        ClientConnection.connect(ipcServerSocketPath, (short) 8033, socketOptions, null, clientBootstrap, new ClientConnectionHandler() {
            @Override
            protected void onConnectionSetup(ClientConnection connection, int errorCode) {
                clientConnectionArray[0] = connection;
            }

            @Override
            protected void onProtocolMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {

            }

            @Override
            protected void onConnectionClosed(int closeReason) {

            }
        }).get();

        GGEventStreamConnectMessage connectMessagePayloadStructure =
                GGEventStreamConnectMessage.builder().authToken(authToken).build();
        String payload = OBJECT_MAPPER.writeValueAsString(connectMessagePayloadStructure);
        clientConnectionArray[0].sendProtocolMessage(null, payload.getBytes(StandardCharsets.UTF_8), MessageType.Connect,
                0).get();
        return clientConnectionArray[0];
    }

    public static ClientConnectionContinuation sendOperationRequest(@NonNull ClientConnection clientConnection,
                                      String operationName,
                                      byte[] payload) throws IOException {

        ClientConnectionContinuation clientConnectionContinuation =
                clientConnection.newStream(new ClientConnectionContinuationHandler() {
                    @Override
                    protected void onContinuationMessage(List<Header> headers, byte[] payload, MessageType messageType,
                                                         int messageFlags) {
                        //Nothing to receive for update
                    }
                });
        Header messageType = Header.createHeader(":message-type", (int) MessageType.ApplicationMessage.getEnumValue());
        Header operation = Header.createHeader("operation", operationName);
        Header streamId = Header.createHeader(":stream-id", STREAM_ID++);

        List<Header> messageHeaders = new ArrayList<>(3);
        messageHeaders.add(messageType);
        messageHeaders.add(streamId);
        messageHeaders.add(operation);

        clientConnectionContinuation.activate(operationName, messageHeaders, payload
                , MessageType.ApplicationMessage, 0);
        return clientConnectionContinuation;
    }

    public static ClientConnectionContinuation sendSubscribeOperationRequest(@NonNull ClientConnection clientConnection,
                                                     String operationName,
                                                     byte[] payload,
                                                     BiConsumer<List<Header>,byte[]> callback) throws IOException {

        ClientConnectionContinuation clientConnectionContinuation =
                clientConnection.newStream(new ClientConnectionContinuationHandler() {
                    @Override
                    protected void onContinuationMessage(List<Header> headers, byte[] payload, MessageType messageType,
                                                         int messageFlags) {
                        callback.accept(headers, payload);
                    }
                });
        Header messageType = Header.createHeader(":message-type", (int) MessageType.ApplicationMessage.getEnumValue());
        Header operation = Header.createHeader("operation", operationName);
        Header messageFlags = Header.createHeader(":message-flags", 0);
        Header streamId = Header.createHeader(":stream-id", STREAM_ID++);

        List<Header> messageHeaders = new ArrayList<>(3);
        messageHeaders.add(messageType);
        messageHeaders.add(messageFlags);
        messageHeaders.add(streamId);
        messageHeaders.add(operation);

        clientConnectionContinuation.activate(operationName, messageHeaders, payload
                , MessageType.ApplicationMessage, 0);
        return clientConnectionContinuation;
    }

}
