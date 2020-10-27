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
import software.amazon.awssdk.crt.eventstream.ClientConnection;
import software.amazon.awssdk.crt.eventstream.ClientConnectionHandler;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.AuthorizationHandler;
import software.amazon.awssdk.eventstreamrpc.GreengrassEventStreamConnectMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.ipc.IPCEventStreamService.IPC_SERVER_DOMAIN_SOCKET_FILENAME;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class IPCEventStreamServiceTest {
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
    private GreengrassCoreIPCService greengrassCoreIPCService;
    @Mock
    private software.amazon.awssdk.eventstreamrpc.AuthenticationHandler mockAuthenticationHandler;
    @Mock
    private AuthorizationHandler mockAuthorizationHandler;

    @BeforeEach
    public void setup() {
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
        ipcEventStreamService.startup();
    }

    @AfterEach
    public void tearDown() {
        ipcEventStreamService.close();
    }

    @Test
    public void testClientConnection() throws InterruptedException, IOException, ExecutionException {
        final ClientConnection[] clientConnectionArray = {null};
        CountDownLatch connectionLatch = new CountDownLatch(1);

        try (EventLoopGroup elg = new EventLoopGroup(1);
             ClientBootstrap clientBootstrap = new ClientBootstrap(elg, new HostResolver(elg));
             SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC()) {

            String ipcServerSocketPath = mockRootPath.resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
            ClientConnection
                    .connect(ipcServerSocketPath, (short) DEFAULT_PORT_NUMBER, socketOptions, null, clientBootstrap, new ClientConnectionHandler() {
                        @Override
                        protected void onConnectionSetup(ClientConnection connection, int errorCode) {
                            connectionLatch.countDown();
                            clientConnectionArray[0] = connection;
                        }

                        @Override
                        protected void onProtocolMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {

                        }

                        @Override
                        protected void onConnectionClosed(int closeReason) {

                        }
                    }).get();
            assertTrue(connectionLatch.await(2, TimeUnit.SECONDS));
            GreengrassEventStreamConnectMessage connectMessagePayloadStructure =
                    new GreengrassEventStreamConnectMessage();
            connectMessagePayloadStructure.setAuthToken("authToken");
            String payload = OBJECT_MAPPER.writeValueAsString(connectMessagePayloadStructure);
            clientConnectionArray[0].sendProtocolMessage(null, payload.getBytes(StandardCharsets.UTF_8),
                    MessageType.Connect, 0).get();
            clientConnectionArray[0].closeConnection(0);
        }
    }
}
