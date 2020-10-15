package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.ipc.common.GGEventStreamConnectMessage;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import generated.software.amazon.awssdk.iot.greengrass.GreengrassCoreIPCService;
import generated.software.amazon.awssdk.iot.greengrass.GreengrassCoreIPCServiceModel;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.eventstream.iot.server.AuthenticationData;
import software.amazon.eventstream.iot.server.Authorization;
import software.amazon.eventstream.iot.server.DebugLoggingOperationHandler;
import software.amazon.eventstream.iot.server.IpcServer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;

@NoArgsConstructor
public class IPCEventStreamService implements Startable, Closeable {
    public static final int DEFAULT_PORT_NUMBER = 8033;
    private static final ObjectMapper OBJECT_MAPPER =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final String IPC_SERVER_DOMAIN_SOCKET_FILENAME = "ipcEventStreamServer";
    public static final String KERNEL_DOMAIN_SOCKET_FILEPATH = "AWS_GG_KERNEL_DOMAIN_SOCKET_FILEPATH";

    private static Logger logger = LogManager.getLogger(IPCEventStreamService.class);

    private IpcServer ipcServer;

    @Inject
    private Kernel kernel;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    private AuthenticationHandler authenticationHandler;

    @Inject
    private Configuration config;

    private SocketOptions socketOptions;
    private EventLoopGroup eventLoopGroup;
    private String ipcServerSocketPath;

    IPCEventStreamService(Kernel kernel,
                                 GreengrassCoreIPCService greengrassCoreIPCService,
                                 Configuration config) {
        this.kernel = kernel;
        this.greengrassCoreIPCService = greengrassCoreIPCService;
        this.config = config;
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.getAllOperations().forEach(operation -> {
            greengrassCoreIPCService.setOperationHandler(operation,
                    (context) -> new DebugLoggingOperationHandler(GreengrassCoreIPCServiceModel.getInstance()
                            .getOperationModelContext(operation), context));
        });
        greengrassCoreIPCService.setAuthenticationHandler(
                (List<Header> headers, byte[] bytes) -> ipcAuthenticationHandler(headers, bytes));
        greengrassCoreIPCService.setAuthorizationHandler(
                authenticationData -> ipcAuthorizationHandler(authenticationData));

        socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.LOCAL;
        socketOptions.type = SocketOptions.SocketType.STREAM;
        eventLoopGroup = new EventLoopGroup(1);
        ipcServerSocketPath = kernel.getNucleusPaths().rootPath()
                .resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
        Topic kernelUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_DOMAIN_SOCKET_FILEPATH);
        kernelUri.withValue(ipcServerSocketPath);
        ipcServer = new IpcServer(eventLoopGroup, socketOptions, null, ipcServerSocketPath,
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
            logger.atError().setCause(e).log(errorMessage);
            // TODO: Add BadRequestException to smithy model
            throw new RuntimeException(errorMessage);
        }
        if (Utils.isEmpty(authToken)) {
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
        // TODO: Future does not complete, wait on them when fixed.
        if (ipcServer != null) {
            ipcServer.stopServer();
        }
        eventLoopGroup.close();
        socketOptions.close();
        if (ipcServerSocketPath != null && Files.exists(Paths.get(ipcServerSocketPath))) {
            try {
                logger.atInfo().log("Deleting the ipc server socket descriptor file");
                Files.delete(Paths.get(ipcServerSocketPath));
            } catch (IOException e) {
                logger.atWarn().log("Failed to delete the ipc server socket descriptor file");
            }
        }
    }
}
