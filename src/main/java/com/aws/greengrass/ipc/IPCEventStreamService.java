package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.common.GGEventStreamConnectMessage;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import generated.software.amazon.awssdk.iot.greengrass.GreengrassCoreIPCService;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.eventstream.iot.server.AuthenticationData;
import software.amazon.eventstream.iot.server.Authorization;
import software.amazon.eventstream.iot.server.DebugLoggingOperationHandler;
import software.amazon.eventstream.iot.server.IpcServer;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;

@NoArgsConstructor
public class IPCEventStreamService implements Startable, Closeable {
    public static final int DEFAULT_PORT_NUMBER = 8033;
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final ObjectMapper OBJECT_MAPPER =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Logger logger = LogManager.getLogger(IPCEventStreamService.class);

    private IpcServer ipcServer;

    @Inject
    private Kernel kernel;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    private AuthenticationHandler authenticationHandler;

    private SocketOptions socketOptions;
    private EventLoopGroup eventLoopGroup;

    public IPCEventStreamService(Kernel kernel,
                                 GreengrassCoreIPCService greengrassCoreIPCService) {
        this.kernel = kernel;
        this.greengrassCoreIPCService = greengrassCoreIPCService;
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.getAllOperations().forEach(operation -> {
            greengrassCoreIPCService.setOperationHandler(operation,
                    (context) -> new DebugLoggingOperationHandler(operation, context));
        });
        greengrassCoreIPCService.setAuthenticationHandler(
                (List<Header> headers, byte[] bytes) -> ipcAuthenticationHandler(headers, bytes));
        greengrassCoreIPCService.setAuthorizationHandler(
                authenticationData -> ipcAuthorizationHandler(authenticationData));

        socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.IPv4;
        socketOptions.type = SocketOptions.SocketType.STREAM;
        eventLoopGroup = new EventLoopGroup(1);
        ipcServer = new IpcServer(eventLoopGroup, socketOptions, null, LOCAL_HOST,
                DEFAULT_PORT_NUMBER, greengrassCoreIPCService);
        ipcServer.runServer();
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private Authorization ipcAuthorizationHandler(AuthenticationData authenticationData) {
        // No authorization on service level exist for whole IPC right now so returning ACCEPT for all authenticated
        // clients
        return Authorization.ACCEPT;
    }

    @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.PreserveStackTrace"})
    private AuthenticationData ipcAuthenticationHandler(List<Header> headers, byte[] payload) {
        String authToken = null;

        try {
            GGEventStreamConnectMessage connectMessage = OBJECT_MAPPER.readValue(payload,
                    GGEventStreamConnectMessage.class);
            authToken = connectMessage.getAuthToken();
        } catch (IOException e) {
            String errorMessage = "Invalid auth token in connect message";
            logger.atError().log(errorMessage);
            // TODO: Add BadRequestException to smithy model
            throw new RuntimeException(errorMessage);
        }
        if (StringUtils.isEmpty(authToken)) {
            String errorMessage = "Received empty auth token to authenticate IPC client";
            logger.atError().log(errorMessage);
            throw new RuntimeException(errorMessage);
        }
        AuthenticationData authenticationData;
        try {
            final String serviceName = authenticationHandler.doAuthentication(authToken);
            authenticationData = new AuthenticationData() {
                @Override
                public String getIdentityLabel() {
                    return serviceName;
                }
            };
        } catch (UnauthenticatedException e) {
            throw new RuntimeException("Unrecognized client connecting to GGC over IPC");
        }
        return authenticationData;
    }

    @Override
    public void close() {
        ipcServer.stopServer();
        socketOptions.close();
        eventLoopGroup.close();
    }
}
