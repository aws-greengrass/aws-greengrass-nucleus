/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.MqttRequestException;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.PublishResponse;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.Unsubscribe;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.SubscribeToIoTCoreResponseHandler;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
class IPCMqttProxyTest {
    private static final Logger logger = LogManager.getLogger(IPCMqttProxyTest.class);
    private static final int TIMEOUT_FOR_MQTTPROXY_SECONDS = 20;
    private static final List<String> TEST_PUBLISH_TOPICS = Arrays.asList(
            "A/B/C/DEF/G/H",
            "A/A/C/D",
            "X/Y12/3Z/4/5/6",
            "X/YZ/"
    );
    private static final List<String> TEST_SUBSCRIBE_TOPICS = Arrays.asList(
            "A/B/C/DEF/G/H",
            "A/+/C/DEF/G/+",
            "A/A/C/D",
            "X/Y12/3Z/4/5/6",
            "X/Y12/3Z/#",
            "X/YZ/"
    );
    private static final byte[] TEST_PAYLOAD = "TestPayload".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path tempRootDir;

    private static MqttClient mqttClient;
    private static Kernel kernel;
    private static EventStreamRPCConnection clientConnection;
    private static SocketOptions socketOptions;

    @BeforeEach
    void beforeEach() throws Exception {
        mqttClient = mock(MqttClient.class);
        when(mqttClient.publish(any(Publish.class))).thenReturn(new PublishResponse());
        lenient().when(mqttClient.subscribe(any(Subscribe.class))).thenReturn(CompletableFuture.completedFuture(null));
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                IPCMqttProxyTest.class.getResource("mqttproxy.yaml"));
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
    }

    @AfterEach
    void afterEach() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        kernel.shutdown();
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_called_publish_THEN_message_published() throws Exception {
        int c = 0;
        for (String publishTopic : TEST_PUBLISH_TOPICS) {
            c += 1;
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
            publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
            publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
            publishToIoTCoreRequest.setTopicName(publishTopic);
            greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse()
                    .get(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS);

            System.out.println(publishTopic);
            ArgumentCaptor<Publish> publishRequestArgumentCaptor = ArgumentCaptor.forClass(Publish.class);
            verify(mqttClient, times(c)).publish(publishRequestArgumentCaptor.capture());
            Publish capturedPublishRequest = publishRequestArgumentCaptor.getValue();
            assertThat(capturedPublishRequest.getPayload(), is(TEST_PAYLOAD));
            assertThat(capturedPublishRequest.getTopic(), is(publishTopic));
            assertThat(capturedPublishRequest.getQos(), is(com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE));
        }
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_called_subscribe_THEN_subscribed_and_message_received()
            throws Exception {
        int c = 0;
        for (String subscribeTopic : TEST_SUBSCRIBE_TOPICS) {
            c += 1;
            CountDownLatch messageLatch = new CountDownLatch(1);
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
            subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
            subscribeToIoTCoreRequest.setTopicName(subscribeTopic);

            StreamResponseHandler<IoTCoreMessage> streamResponseHandler = new StreamResponseHandler<IoTCoreMessage>() {
                @Override
                public void onStreamEvent(IoTCoreMessage streamEvent) {
                    if (Arrays.equals(streamEvent.getMessage().getPayload(), TEST_PAYLOAD)
                            && streamEvent.getMessage().getTopicName().equals(subscribeTopic)) {
                        messageLatch.countDown();
                    }
                }

                @Override
                public boolean onStreamError(Throwable error) {
                    logger.atError().cause(error).log("Subscribe stream errored");
                    return false;
                }

                @Override
                public void onStreamClosed() {

                }
            };

            SubscribeToIoTCoreResponseHandler responseHandler = greengrassCoreIPCClient.subscribeToIoTCore(
                    subscribeToIoTCoreRequest, Optional.of(streamResponseHandler));
            responseHandler.getResponse().get(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS);

            ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor
                    = ArgumentCaptor.forClass(Subscribe.class);
            verify(mqttClient, times(c)).subscribe(subscribeRequestArgumentCaptor.capture());
            Subscribe capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            assertThat(capturedSubscribeRequest.getTopic(), is(subscribeTopic));
            assertThat(capturedSubscribeRequest.getQos(), is(com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE));

            Consumer<Publish> callback = capturedSubscribeRequest.getCallback();
            Publish message = Publish.builder().topic(subscribeTopic).payload(TEST_PAYLOAD).build();
            callback.accept(message);
            assertTrue(messageLatch.await(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS));

            //close stream -> unsubscribe
            responseHandler.closeStream();
            Thread.sleep(500);

            ArgumentCaptor<Unsubscribe> unsubscribeRequestArgumentCaptor
                    = ArgumentCaptor.forClass(Unsubscribe.class);
            verify(mqttClient, times(c)).unsubscribe(unsubscribeRequestArgumentCaptor.capture());
            Unsubscribe capturedUnsubscribeRequest = unsubscribeRequestArgumentCaptor.getValue();
            assertThat(capturedUnsubscribeRequest.getTopic(), is(subscribeTopic));
            assertThat(capturedUnsubscribeRequest.getSubscriptionCallback(), is(callback));
        }
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_publish_throws_error_THEN_client_gets_error(ExtensionContext context)
            throws InterruptedException, MqttRequestException, SpoolerStoreException {
        ignoreExceptionOfType(context, ExecutionException.class);
        ignoreExceptionOfType(context, SpoolerStoreException.class);
        ignoreExceptionOfType(context, ServiceError.class);

        String spoolerExceptionMessage = "Spooler queue is full and new message would not be added into spooler";
        when(mqttClient.publish(any(Publish.class))).thenThrow(new SpoolerStoreException(spoolerExceptionMessage));

        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        publishToIoTCoreRequest.setTopicName(TEST_PUBLISH_TOPICS.get(0));

        String clientException = "";
        try {
            greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse().get();
        } catch (ExecutionException e) {
            clientException = e.getCause().getMessage();
        }
        assertThat(clientException, containsString(spoolerExceptionMessage));
    }
}
