/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCServiceModel;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.Authorization;
import software.amazon.awssdk.eventstreamrpc.AuthorizationHandler;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnectionConfig;
import software.amazon.awssdk.eventstreamrpc.GreengrassConnectMessageSupplier;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.util.platforms.unix.UnixPlatform.IPC_SERVER_DOMAIN_SOCKET_FILENAME;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IPCEventStreamServiceTest {
    private IPCEventStreamService ipcEventStreamService;
    protected static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TempDir
    Path mockRootPath;

    @Mock
    private Kernel mockKernel;

    @Mock
    private Configuration config;

    @Mock
    private Topics mockRootTopics;

    @Mock
    private Topic mockTopic;

    @Mock
    private Topic mockRelativePath;

    @Mock
    private GreengrassCoreIPCService greengrassCoreIPCService;
    @Mock
    private software.amazon.awssdk.eventstreamrpc.AuthenticationHandler mockAuthenticationHandler;
    @Mock
    private AuthorizationHandler mockAuthorizationHandler;

    @BeforeEach
    public void setup() {
        AuthenticationData authenticationData = new AuthenticationData() {
            @Override
            public String getIdentityLabel() {
                return "EventStreamConnectionTest";
            }
        };
        when(mockAuthenticationHandler.apply(any(), any())).thenReturn(authenticationData);
        when(mockAuthorizationHandler.apply(eq(authenticationData))).thenReturn(Authorization.ACCEPT);
        when(greengrassCoreIPCService.getAuthenticationHandler()).thenReturn(mockAuthenticationHandler);
        when(greengrassCoreIPCService.getAuthorizationHandler()).thenReturn(mockAuthorizationHandler);
        when(greengrassCoreIPCService.getServiceModel()).thenReturn(GreengrassCoreIPCServiceModel.getInstance());

        ipcEventStreamService = new IPCEventStreamService(mockKernel, greengrassCoreIPCService, config);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        when(mockKernel.getNucleusPaths()).thenReturn(nucleusPaths);
        when(nucleusPaths.rootPath()).thenReturn(mockRootPath);
        when(config.getRoot()).thenReturn(mockRootTopics);
        when(mockRootTopics.lookup(eq(SETENV_CONFIG_NAMESPACE),
                eq(NUCLEUS_DOMAIN_SOCKET_FILEPATH))).thenReturn(mockTopic);
        when(mockRootTopics.lookup(eq(SETENV_CONFIG_NAMESPACE),
                eq(NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT))).thenReturn(mockRelativePath);
        ipcEventStreamService.startup();
    }

    @AfterEach
    public void tearDown() {
        ipcEventStreamService.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testClientConnection() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        EventStreamRPCConnection connection = null;
        try (EventLoopGroup elg = new EventLoopGroup(1);
             ClientBootstrap clientBootstrap = new ClientBootstrap(elg, new HostResolver(elg));
             SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC()) {

            String ipcServerSocketPath = mockRootPath.resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
            final EventStreamRPCConnectionConfig config = new EventStreamRPCConnectionConfig(clientBootstrap, elg, socketOptions, null, ipcServerSocketPath, DEFAULT_PORT_NUMBER, GreengrassConnectMessageSupplier
                    .connectMessageSupplier("authToken"));
            connection = new EventStreamRPCConnection(config);
            final boolean disconnected[] = {false};
            final int disconnectedCode[] = {-1};
            //this is a bit cumbersome but does not prevent a convenience wrapper from exposing a sync
            //connect() or a connect() that returns a CompletableFuture that errors
            //this could be wrapped by utility methods to provide a more
            connection.connect(new EventStreamRPCConnection.LifecycleHandler() {
                @Override
                public void onConnect() {
                    connectionLatch.countDown();
                }

                @Override
                public void onDisconnect(int errorCode) {
                    disconnected[0] = true;
                    disconnectedCode[0] = errorCode;
                }

                //This on error is for any errors that is connection level, including problems during connect()
                @Override
                public boolean onError(Throwable t) {
                    return true;    //hints at handler to disconnect due to this error
                }
            });
            assertTrue(connectionLatch.await(2, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
