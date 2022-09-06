/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.common.DefaultOperationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCServiceModel;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.Authorization;
import software.amazon.awssdk.eventstreamrpc.GreengrassEventStreamConnectMessage;
import software.amazon.awssdk.eventstreamrpc.RpcServer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;

public class IPCEventStreamService implements Startable, Closeable {
    public static final long DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS = 5;
    public static final int DEFAULT_PORT_NUMBER = 8033;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final String NUCLEUS_DOMAIN_SOCKET_FILEPATH = "AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH";
    public static final String NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT =
            "AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT";

    private static final Logger logger = LogManager.getLogger(IPCEventStreamService.class);

    private RpcServer rpcServer;

    private final Kernel kernel;

    private final GreengrassCoreIPCService greengrassCoreIPCService;

    private final AuthenticationHandler authenticationHandler;

    private final Configuration config;

    private SocketOptions socketOptions;
    private EventLoopGroup eventLoopGroup;

    private final DeviceConfiguration deviceConfiguration;

    @Inject
    IPCEventStreamService(Kernel kernel,
                          GreengrassCoreIPCService greengrassCoreIPCService,
                          Configuration config,
                          AuthenticationHandler authenticationHandler) {
        this.kernel = kernel;
        this.deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        this.greengrassCoreIPCService = greengrassCoreIPCService;
        this.config = config;
        this.authenticationHandler = authenticationHandler;
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.ExceptionAsFlowControl"})
    @Override
    public void startup() throws IOException {
        Path rootPath = kernel.getNucleusPaths().rootPath();

        try {
            greengrassCoreIPCService.getAllOperations().forEach(operation ->
                    greengrassCoreIPCService.setOperationHandler(operation,
                            (context) -> new DefaultOperationHandler(GreengrassCoreIPCServiceModel.getInstance()
                                    .getOperationModelContext(operation), context)));
            greengrassCoreIPCService.setAuthenticationHandler((List<Header> headers, byte[] bytes) ->
                    ipcAuthenticationHandler(bytes));
            greengrassCoreIPCService.setAuthorizationHandler(this::ipcAuthorizationHandler);

            socketOptions = new SocketOptions();
            socketOptions.connectTimeoutMs = 3000;
            socketOptions.domain = SocketOptions.SocketDomain.LOCAL;
            socketOptions.type = SocketOptions.SocketType.STREAM;
            eventLoopGroup = new EventLoopGroup(1);

            Topic kernelUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH);

            logger.atInfo().kv("IPCService: ", Coerce.toString(deviceConfiguration.getIpcSocketPath())).log("JJ IPCService 中读取配置的ipcpath: ");
            kernelUri.withValue(Platform.getInstance().prepareIpcFilepath(rootPath, deviceConfiguration));
            Topic kernelRelativeUri =
                    config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT);
            kernelRelativeUri.withValue(Platform.getInstance().prepareIpcFilepathForComponent(rootPath, deviceConfiguration));

            // For domain sockets:
            // 1. Port number is ignored. RpcServer does not accept a null value so we are using a default value.
            // 2. The hostname parameter expects the socket filepath
            rpcServer = new RpcServer(eventLoopGroup, socketOptions, null,
                    Platform.getInstance().prepareIpcFilepathForRpcServer(rootPath, deviceConfiguration),
                    DEFAULT_PORT_NUMBER, greengrassCoreIPCService);
            rpcServer.runServer();
        } catch (RuntimeException | IOException e) {
            logger.atError("rootPath-jj:" + rootPath);
            // Make sure to cleanup anything we created since we don't know where exactly we failed
            close();
            throw e;
        }
        logger.debug("Set IPC permissions");
        Platform.getInstance().setIpcFilePermissions(rootPath, deviceConfiguration);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private Authorization ipcAuthorizationHandler(AuthenticationData authenticationData) {
        // No authorization on service level exist for whole IPC right now so returning ACCEPT for all authenticated
        // clients
        return Authorization.ACCEPT;
    }

    @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.PreserveStackTrace"})
    private AuthenticationData ipcAuthenticationHandler(byte[] payload) {
        String authToken = null;

        try {
            GreengrassEventStreamConnectMessage connectMessage = OBJECT_MAPPER.readValue(payload,
                    GreengrassEventStreamConnectMessage.class);
            authToken = connectMessage.getAuthToken();
        } catch (IOException e) {
            String errorMessage = "Invalid auth token in connect message";
            logger.atError().setCause(e).log(errorMessage);
            // GG_NEEDS_REVIEW: TODO: Add BadRequestException to smithy model
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
            authenticationData = () -> serviceName;
        } catch (UnauthenticatedException e) {
            throw new RuntimeException("Unrecognized client connecting to GGC over IPC");
        }
        return authenticationData;
    }

    @Override
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidCatchingGenericException"})
    public void close() {
        // GG_NEEDS_REVIEW: TODO: Future does not complete, wait on them when fixed.
        if (rpcServer != null) {
            rpcServer.stopServer();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.close();
            // GG_NEEDS_REVIEW: TODO: Wait for ELG to close. Right now the future does not complete, thus timing out.
        }
        if (socketOptions != null) {
            socketOptions.close();
        }

        Platform.getInstance().cleanupIpcFiles(kernel.getNucleusPaths().rootPath(), deviceConfiguration);
    }
}
