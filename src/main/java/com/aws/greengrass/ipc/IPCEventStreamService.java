/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCServiceModel;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.Authorization;
import software.amazon.awssdk.eventstreamrpc.DebugLoggingOperationHandler;
import software.amazon.awssdk.eventstreamrpc.GreengrassEventStreamConnectMessage;
import software.amazon.awssdk.eventstreamrpc.IpcServer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.List;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;

@NoArgsConstructor
public class IPCEventStreamService implements Startable, Closeable {
    public static final long DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS = 5;
    public static final int DEFAULT_PORT_NUMBER = 8033;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final String IPC_SERVER_DOMAIN_SOCKET_FILENAME = "ipcEventStreamServer.socket";
    public static final String IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK = "./nucleusRoot/ipc.socket";
    public static final String NUCLEUS_ROOT_PATH_SYMLINK = "./nucleusRoot";
    // This is relative to component's CWD
    // components CWD is <kernel-root-path>/work/component
    public static final String IPC_SERVER_DOMAIN_SOCKET_RELATIVE_FILENAME = "../../ipc.socket";

    public static final String NUCLEUS_DOMAIN_SOCKET_FILEPATH = "AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH";
    public static final String NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT =
            "AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT";

    // https://www.gnu.org/software/libc/manual/html_node/Local-Namespace-Details.html
    private static final int UDS_SOCKET_PATH_MAX_LEN = 108;

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
    @Getter
    private String ipcServerSocketAbsolutePath;

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
                (List<Header> headers, byte[] bytes) -> ipcAuthenticationHandler(bytes));
        greengrassCoreIPCService.setAuthorizationHandler(
                authenticationData -> ipcAuthorizationHandler(authenticationData));

        socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.LOCAL;
        socketOptions.type = SocketOptions.SocketType.STREAM;
        eventLoopGroup = new EventLoopGroup(1);
        ipcServerSocketAbsolutePath = kernel.getNucleusPaths().rootPath()
                .resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();

        if (Files.exists(Paths.get(ipcServerSocketAbsolutePath))) {
            try {
                logger.atDebug().log("Deleting the ipc server socket descriptor file");
                Files.delete(Paths.get(ipcServerSocketAbsolutePath));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file");
            }
        }

        Topic kernelUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH);
        kernelUri.withValue(ipcServerSocketAbsolutePath);
        Topic kernelRelativeUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE,
                NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT);
        kernelRelativeUri.withValue(ipcServerSocketAbsolutePath);

        boolean symLinkCreated = false;

        try {
            // Usually we do not want to write outside of kernel root. Because of socket path length limitations we
            // will create a symlink only if needed
            if (ipcServerSocketAbsolutePath.length() > UDS_SOCKET_PATH_MAX_LEN) {
                Files.createSymbolicLink(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK), kernel.getNucleusPaths().rootPath());
                kernelRelativeUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE,
                        NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT);
                kernelRelativeUri.withValue(IPC_SERVER_DOMAIN_SOCKET_RELATIVE_FILENAME);
                symLinkCreated = true;
            }

        } catch (IOException e) {
            logger.atError().setCause(e).log("Cannot setup symlinks for the ipc server socket path and the socket "
                    + "filepath is longer than 108 chars so unable to start IPC server");
            return;
        }

        // For domain sockets:
        // 1. Port number is ignored. IpcServer does not accept a null value so we are using a default value.
        // 2. The hostname parameter expects the socket filepath
        ipcServer = new IpcServer(eventLoopGroup, socketOptions, null,
                symLinkCreated ? IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK : ipcServerSocketAbsolutePath,
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void close() {

        if (Files.exists(Paths.get(IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK), LinkOption.NOFOLLOW_LINKS)) {
            try {
                logger.atDebug().log("Deleting the ipc server socket descriptor file symlink");
                Files.delete(Paths.get(IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file symlink");
            }
        }

        // GG_NEEDS_REVIEW: TODO: Future does not complete, wait on them when fixed.
        if (ipcServer != null) {
            ipcServer.stopServer();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.close();
            // GG_NEEDS_REVIEW: TODO: Wait for ELG to close. Right now the future does not complete, thus timing out.
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        // Removing it during close as CWD might change on next run
        if (Files.exists(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK), LinkOption.NOFOLLOW_LINKS)) {
            try {
                logger.atDebug().log("Deleting the nucleus root path symlink");
                Files.delete(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file symlink");
            }
        }
    }
}
