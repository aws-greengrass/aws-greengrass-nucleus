/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.mqttclient;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith(GGExtension.class)
public class MqttClientPublishTest extends BaseITCase {

    private static Kernel kernel;
    private static EventStreamRPCConnection clientConnection;
    private static SocketOptions socketOptions;
    private static final byte[] TEST_GOOD_PAYLOAD = "goodPayload".getBytes(StandardCharsets.UTF_8);
    private static final int TIMEOUT_FOR_MQTTPROXY_SECONDS = 20;
    private static final String TEST_GOOD_PUBLISH_TOPIC = "A/B/C";
    private static ExecutorService executorService = TestUtils.synchronousExecutorService();
    private static Configuration config;
    private static AwsIotMqttConnectionBuilder builder;
    private static DeviceConfiguration deviceConfiguration;
    private static Spool spool;
    private static MqttClientConnection connection;
    private static MqttClient mqttClient;
    private static GreengrassCoreIPCClient greengrassCoreIPCClient;

    @BeforeEach
    void before() throws IOException, InterruptedException, ExecutionException {

        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                MqttClientPublishTest.class.getResource("config.yaml"));
        config = kernel.getConfig();

        deviceConfiguration = mock(DeviceConfiguration.class);
        spool = mock(Spool.class);
        builder =  mock(AwsIotMqttConnectionBuilder.class);
        connection = mock(MqttClientConnection.class);

        Topics mqttNamespace = config.lookupTopics("mqtt");
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqttNamespace);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        lenient().when(builder.build()).thenReturn(connection);
        lenient().when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        lenient().when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(connection.publish(any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(0));

        mqttClient = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));

        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TEST_SERVICE_NAME) && newState.equals(State.FINISHED)) {
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
        greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
    }

    @AfterEach
    void after() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        executorService.shutdownNow();
        kernel.shutdown();
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_publish_THEN_message_published() throws Exception {

        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_GOOD_PAYLOAD);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        publishToIoTCoreRequest.setTopicName(TEST_GOOD_PUBLISH_TOPIC);

        greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse()
                .get(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS);

        ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(mqttClient).publish(publishRequestArgumentCaptor.capture());
        PublishRequest capturedPublishRequest = publishRequestArgumentCaptor.getValue();
        assertThat(capturedPublishRequest.getPayload(), is(TEST_GOOD_PAYLOAD));
        assertThat(capturedPublishRequest.getTopic(), is(TEST_GOOD_PUBLISH_TOPIC));
        assertThat(capturedPublishRequest.getQos(), is(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_publish_bad_request_THEN_published_exceptionally() throws InterruptedException, ExecutionException, TimeoutException {

        String TEST_BAD_TOPIC_WITH_WILDCARD = "A/B/#";
        String TEST_BAD_TOPIC_EXCEED_MAX_SLASH_NUM = "A/B/C/D/E/F/G/H/I";
        String TEST_BAD_TOPIC_EXCEED_MAX_LENGTH = "A/" + String.join("", Collections.nCopies(MqttClient.MAX_LENGTH_OF_TOPIC + 1, "a"));
        byte[] TEST_BAD_PAYLOAD_EXCEED_MAX_LENGTH = String.join("", Collections.nCopies(
                MqttClient.DEFAULT_MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES + 1, "a"))
                .getBytes(StandardCharsets.UTF_8);

        List<Pair<String, byte[]>> badRequests = new ArrayList<Pair<String, byte[]>>() {{
            add(new Pair<>(TEST_BAD_TOPIC_WITH_WILDCARD, TEST_GOOD_PAYLOAD));
            add(new Pair<>(TEST_BAD_TOPIC_EXCEED_MAX_SLASH_NUM, TEST_GOOD_PAYLOAD));
            add(new Pair<>(TEST_BAD_TOPIC_EXCEED_MAX_LENGTH, TEST_GOOD_PAYLOAD));
            add(new Pair<>(TEST_GOOD_PUBLISH_TOPIC, TEST_BAD_PAYLOAD_EXCEED_MAX_LENGTH));
        }};

        for (Pair<String, byte[]> requestTopicPayload : badRequests) {
            assertTrue(whenPublishGetSpoolerException(requestTopicPayload));
        }
    }

    private boolean whenPublishGetSpoolerException(Pair<String, byte[]> requestTopicPayload) {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setTopicName(requestTopicPayload.getLeft());
        publishToIoTCoreRequest.setPayload(requestTopicPayload.getRight());
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        try {
            greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse().get();
        } catch (ExecutionException | InterruptedException e) {
            return true;
        }
        return false;
    }
}
