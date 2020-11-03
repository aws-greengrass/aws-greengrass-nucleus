/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.mqttclient.UnsubscribeRequest;
import com.aws.greengrass.mqttclient.spool.SpoolerLoadException;
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
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
public class IPCMqttProxyTest {
    private static final Logger logger = LogManager.getLogger(IPCMqttProxyTest.class);
    private static final int TIMEOUT_FOR_MQTTPROXY_SECONDS = 20;
    private static final String TEST_PUBLISH_TOPIC = "A/B/C";
    private static final String TEST_SUBSCRIBE_TOPIC = "X/Y/Z/#";
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
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        kernel = new Kernel();
        kernel.parseArgs("-i", IPCMqttProxyTest.class.getResource("mqttproxy.yaml").toString());
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
        CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
        completableFuture.complete(0);
        when(mqttClient.publish(any())).thenReturn(completableFuture);

        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        publishToIoTCoreRequest.setTopicName(TEST_PUBLISH_TOPIC);
        publishToIoTCoreRequest.setRetain(false);
        greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse()
                .get(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS);

        ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(mqttClient).publish(publishRequestArgumentCaptor.capture());
        PublishRequest capturedPublishRequest = publishRequestArgumentCaptor.getValue();
        assertThat(capturedPublishRequest.getPayload(), is(TEST_PAYLOAD));
        assertThat(capturedPublishRequest.getTopic(), is(TEST_PUBLISH_TOPIC));
        assertThat(capturedPublishRequest.isRetain(), is(false));
        assertThat(capturedPublishRequest.getQos(), is(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_called_subscribe_THEN_subscribed_and_message_received()
            throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        subscribeToIoTCoreRequest.setTopicName(TEST_SUBSCRIBE_TOPIC);

        StreamResponseHandler<IoTCoreMessage> streamResponseHandler = new StreamResponseHandler<IoTCoreMessage>() {
            @Override
            public void onStreamEvent(IoTCoreMessage streamEvent) {
                if (Arrays.equals(streamEvent.getMessage().getPayload(), TEST_PAYLOAD)
                        && streamEvent.getMessage().getTopicName().equals(TEST_SUBSCRIBE_TOPIC)) {
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

        ArgumentCaptor<SubscribeRequest> subscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
        SubscribeRequest capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
        assertThat(capturedSubscribeRequest.getTopic(), is(TEST_SUBSCRIBE_TOPIC));
        assertThat(capturedSubscribeRequest.getQos(), is(QualityOfService.AT_LEAST_ONCE));

        Consumer<MqttMessage> callback = capturedSubscribeRequest.getCallback();
        MqttMessage message = new MqttMessage(TEST_SUBSCRIBE_TOPIC, TEST_PAYLOAD);
        callback.accept(message);
        assertTrue(messageLatch.await(TIMEOUT_FOR_MQTTPROXY_SECONDS, TimeUnit.SECONDS));

        //close stream -> unsubscribe
        responseHandler.closeStream();
        Thread.sleep(5);

        ArgumentCaptor<UnsubscribeRequest> unsubscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(UnsubscribeRequest.class);
        verify(mqttClient).unsubscribe(unsubscribeRequestArgumentCaptor.capture());
        UnsubscribeRequest capturedUnsubscribeRequest = unsubscribeRequestArgumentCaptor.getValue();
        assertThat(capturedUnsubscribeRequest.getTopic(), is(TEST_SUBSCRIBE_TOPIC));
        assertThat(capturedUnsubscribeRequest.getCallback(), is(callback));
    }

    @Test
    void GIVEN_MqttProxyEventStreamClient_WHEN_publish_throws_error_THEN_client_gets_error(ExtensionContext context)
            throws InterruptedException {
        ignoreExceptionOfType(context, ExecutionException.class);
        ignoreExceptionOfType(context, SpoolerLoadException.class);
        ignoreExceptionOfType(context, ServiceError.class);

        CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
        String spoolerExceptionMessage = "Spooler queue is full and new message would not be added into spooler";
        completableFuture.completeExceptionally(new SpoolerLoadException(spoolerExceptionMessage));
        when(mqttClient.publish(any())).thenReturn(completableFuture);
        String expectedSpoolerException = SpoolerLoadException.class.getCanonicalName() + ": " + spoolerExceptionMessage;

        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        publishToIoTCoreRequest.setTopicName(TEST_PUBLISH_TOPIC);
        publishToIoTCoreRequest.setRetain(false);

        String clientException = "";
        try {
            greengrassCoreIPCClient.publishToIoTCore(publishToIoTCoreRequest, Optional.empty()).getResponse().get();
        } catch (ExecutionException e) {
            clientException = e.getCause().getMessage();
        }
        assertThat(clientException, containsString(expectedSpoolerException));
    }
}
