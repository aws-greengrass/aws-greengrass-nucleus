/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.SubscribeToIoTCoreResponseHandler;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionMode;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt5.Mqtt5Client;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions;
import software.amazon.awssdk.crt.mqtt5.OnConnectionSuccessReturn;
import software.amazon.awssdk.crt.mqtt5.PublishResult;
import software.amazon.awssdk.crt.mqtt5.packets.SubAckPacket;
import software.amazon.awssdk.crt.mqtt5.packets.UnsubAckPacket;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test for a RECEIVE_ONLY subscribe: it runs over real IPC through the real
 * {@link MqttProxyIPCAgent} handler AND the real {@link MqttClient} router. Unlike
 * {@code IPCMqttProxyTest} (which mocks {@code MqttClient}), this wires a real {@code MqttClient} with a mocked
 * CRT MQTT5 stack into the kernel, so the receive-only registration and the router's delivery pass
 * actually execute. It lives in package {@code com.aws.greengrass.mqttclient} so it can reach the
 * package-private {@link MqttClient#getMessageHandlerForClient} to inject an inbound message.
 */
@SuppressWarnings("PMD.CloseResource")
@ExtendWith(GGExtension.class)
public class DirectMessageRoutingIntegTest extends BaseITCase {

    private static final byte[] TEST_PAYLOAD = "directMessagePayload".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_TOPIC = "A/B/C"; // covered by the "A/#" grant in directmessages.yaml
    private static final int TIMEOUT_SECONDS = 20;

    private static final ExecutorService executorService = TestUtils.synchronousExecutorService();
    private Kernel kernel;
    private EventStreamRPCConnection clientConnection;
    private SocketOptions socketOptions;
    private MqttClient mqttClient;
    private Mqtt5Client mockMqtt5Client;

    @BeforeEach
    void before() throws Exception {
        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                DirectMessageRoutingIntegTest.class.getResource("directmessages.yaml"));
        Configuration config = kernel.getConfig();

        DeviceConfiguration deviceConfiguration = mock(DeviceConfiguration.class);
        Spool spool = mock(Spool.class);
        AwsIotMqttConnectionBuilder builder = mock(AwsIotMqttConnectionBuilder.class);
        MqttClientConnection connection = mock(MqttClientConnection.class);

        Topics mqttNamespace = config.lookupTopics("mqtt");
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqttNamespace);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        lenient().when(builder.build()).thenReturn(connection);
        lenient().when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        lenient().when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));

        // Mock the CRT MQTT5 stack so the real MqttClient never opens a cloud connection.
        AwsIotMqtt5ClientBuilder mockMqtt5Builder = mock(AwsIotMqtt5ClientBuilder.class, Answers.RETURNS_SELF);
        lenient().when(builder.toAwsIotMqtt5ClientBuilder()).thenReturn(mockMqtt5Builder);
        mockMqtt5Client = mock(Mqtt5Client.class);
        lenient().when(mockMqtt5Builder.build()).thenReturn(mockMqtt5Client);
        lenient().when(mockMqtt5Client.subscribe(any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SubAckPacket.class, Answers.RETURNS_MOCKS)));
        lenient().when(mockMqtt5Client.unsubscribe(any()))
                .thenReturn(CompletableFuture.completedFuture(mock(UnsubAckPacket.class, Answers.RETURNS_MOCKS)));
        lenient().when(mockMqtt5Client.publish(any()))
                .thenReturn(CompletableFuture.completedFuture(mock(PublishResult.class, Answers.RETURNS_MOCKS)));
        org.mockito.ArgumentCaptor<Mqtt5ClientOptions.LifecycleEvents> lifecycleEventCaptor =
                org.mockito.ArgumentCaptor.forClass(Mqtt5ClientOptions.LifecycleEvents.class);
        lenient().when(mockMqtt5Builder.withLifeCycleEvents(lifecycleEventCaptor.capture()))
                .thenReturn(mockMqtt5Builder);
        lenient().doAnswer((i) -> {
            lifecycleEventCaptor.getValue().onConnectionSuccess(mockMqtt5Client,
                    mock(OnConnectionSuccessReturn.class, Answers.RETURNS_MOCKS));
            return null;
        }).when(mockMqtt5Client).start();

        mqttClient = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));

        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TEST_SERVICE_NAME) && newState.equals(com.aws.greengrass.dependency.State.FINISHED)) {
                awaitIpcServiceLatch.countDown();
            }
        });
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.launch();
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));

        Topics servicePrivateConfig = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC, TEST_SERVICE_NAME,
                PRIVATE_STORE_NAMESPACE_TOPIC);
        String authToken = Coerce.toString(servicePrivateConfig.find(SERVICE_UNIQUE_ID_KEY));
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
    }

    @AfterEach
    void after() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        kernel.shutdown();
    }

    @Test
    void GIVEN_receive_only_subscribe_over_ipc_WHEN_inbound_message_THEN_routed_to_component_with_no_cloud_subscribe()
            throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        SubscribeToIoTCoreRequest subscribeRequest = new SubscribeToIoTCoreRequest();
        subscribeRequest.setTopicName(TEST_TOPIC);
        // RECEIVE_ONLY (direct message), no qos.
        subscribeRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);

        StreamResponseHandler<IoTCoreMessage> streamResponseHandler = new StreamResponseHandler<IoTCoreMessage>() {
            @Override
            public void onStreamEvent(IoTCoreMessage streamEvent) {
                if (java.util.Arrays.equals(streamEvent.getMessage().getPayload(), TEST_PAYLOAD)
                        && streamEvent.getMessage().getTopicName().equals(TEST_TOPIC)) {
                    messageLatch.countDown();
                }
            }

            @Override
            public boolean onStreamError(Throwable error) {
                return false;
            }

            @Override
            public void onStreamClosed() {
            }
        };

        // Subscribe over real IPC with RECEIVE_ONLY mode.
        SubscribeToIoTCoreResponseHandler responseHandler =
                greengrassCoreIPCClient.subscribeToIoTCore(subscribeRequest, Optional.of(streamResponseHandler));
        responseHandler.getResponse().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Inject an inbound message through the real router.
        Publish inbound = Publish.builder().topic(TEST_TOPIC).payload(TEST_PAYLOAD).build();
        mqttClient.getMessageHandlerForClient(mock(AwsIotMqttClient.class)).accept(inbound);

        // The message is streamed back to the component over IPC.
        assertTrue(messageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "RECEIVE_ONLY inbound message was not routed back to the component over IPC");

        // No cloud SUBSCRIBE was ever issued.
        verify(mockMqtt5Client, never()).subscribe(any());

        responseHandler.closeStream();
    }
}
