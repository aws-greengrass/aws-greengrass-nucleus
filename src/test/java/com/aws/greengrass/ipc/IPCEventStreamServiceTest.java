/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnectionConfig;
import software.amazon.awssdk.eventstreamrpc.GreengrassConnectMessageSupplier;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_PORT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IPCEventStreamServiceTest {
    private static final int IPC_TEST_PORT = 20388;

    private IPCEventStreamService ipcEventStreamService;

    @TempDir
    Path mockRootPath;

    @Mock
    NucleusPaths nucleusPaths;

    @Mock
    private Kernel mockKernel;

    @Mock
    private Context mockContext;

    @Mock
    private DeviceConfiguration mockDeviceConfig;

    @Mock
    private Configuration config;

    @Mock(lenient = true)
    private Topics mockRootTopics;

    @Mock
    private Topic mockDomainSocketPath;

    @Mock
    private Topic mockRelativePath;

    @Mock
    private Topic mockIPCSocketPort;

    @Mock
    private Topic mockGreengrassIpcPort;

    @Mock
    private AuthenticationHandler mockAuthenticationHandler;

    @BeforeEach
    public void setup() throws UnauthenticatedException, InterruptedException {
        when(mockKernel.getNucleusPaths()).thenReturn(nucleusPaths);
        when(nucleusPaths.rootPath()).thenReturn(mockRootPath);
        when(mockKernel.getContext()).thenReturn(mockContext);
        when(mockContext.get(DeviceConfiguration.class)).thenReturn(mockDeviceConfig);
        when(mockDeviceConfig.getGreengrassIpcPort()).thenReturn(mockIPCSocketPort);
        when(config.getRoot()).thenReturn(mockRootTopics);
        when(mockRootTopics.lookup(eq(SETENV_CONFIG_NAMESPACE), eq(NUCLEUS_DOMAIN_SOCKET_FILEPATH))).thenReturn(mockDomainSocketPath);
        when(mockRootTopics.lookup(eq(SETENV_CONFIG_NAMESPACE), eq(NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT))).thenReturn(mockRelativePath);
        when(mockRootTopics.lookup(eq(SETENV_CONFIG_NAMESPACE), eq(NUCLEUS_DOMAIN_SOCKET_PORT))).thenReturn(mockIPCSocketPort);
        when(mockAuthenticationHandler.doAuthentication(anyString())).thenReturn("SomeService");

        ipcEventStreamService = new IPCEventStreamService(mockKernel, new GreengrassCoreIPCService(), config,
                mockAuthenticationHandler);
        ipcEventStreamService.startup();
        Thread.sleep(5000);
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

            String ipcServerSocketPath = Platform.getInstance().prepareIpcFilepathForComponent(mockRootPath);
            final EventStreamRPCConnectionConfig config = new EventStreamRPCConnectionConfig(clientBootstrap, elg, socketOptions, null, ipcServerSocketPath, IPC_TEST_PORT, GreengrassConnectMessageSupplier
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
