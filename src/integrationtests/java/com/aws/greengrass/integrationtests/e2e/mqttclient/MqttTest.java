/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.mqttclient;

import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.createCloseableLogListener;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith(GGExtension.class)
@Tag("E2E")
class MqttTest extends BaseE2ETestCase {
    public static final int NUM_MESSAGES = 50;
    public static final String CERT_PATH = "certPath";
    private Kernel kernel;

    protected MqttTest() throws Exception {
        super();
    }

    @AfterEach
    void afterEach() {
        try {
            kernel.shutdown();
        } finally {
            cleanup();
        }
    }

    @Test
    void GIVEN_mqttclient_WHEN_subscribe_and_publish_THEN_receives_all_messages()
            throws IOException, ExecutionException, InterruptedException, TimeoutException, DeviceConfigurationException {
        kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString());
        setDefaultRunWithUser(kernel);
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, TEST_REGION.toString(),
                TES_ROLE_ALIAS_NAME, CERT_PATH);

        MqttClient client = kernel.getContext().get(MqttClient.class);
        CountDownLatch cdl = new CountDownLatch(NUM_MESSAGES);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback((m) -> {
            cdl.countDown();
            logger.atInfo().kv("remaining", cdl.getCount()).log("Received 1 message from cloud.");
        }).build());

        for (int i = 0; i < NUM_MESSAGES; i++) {
            client.publish(PublishRequest.builder().topic("A/B/C").payload("What's up".getBytes(StandardCharsets.UTF_8))
                    .build()).get(5, TimeUnit.SECONDS);
            logger.atInfo().kv("total", i + 1).log("Added 1 message to spooler.");
        }
        assertTrue(cdl.await(1, TimeUnit.MINUTES), "All messages published and received");
    }

    @Test
    void GIVEN_mqttclient_WHEN_closes_new_connection_is_created_THEN_previous_session_is_invalidated()
            throws Throwable {
        kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString());
        setDefaultRunWithUser(kernel);
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, TEST_REGION.toString(),
                TES_ROLE_ALIAS_NAME, CERT_PATH);

        MqttClient client = kernel.getContext().get(MqttClient.class);

        //subscribe to 50 topics using first connection.
        int numberOfTopics = 50;
        for (int i = 0; i < numberOfTopics; i++) {
            client.subscribe(SubscribeRequest.builder().topic("A/" + i).callback((m) -> {}).build());
        }
        //close the first connections and create a second connection.
        client.close();
        client = kernel.getContext().newInstance(MqttClient.class);
        CountDownLatch cdl = new CountDownLatch(numberOfTopics);
        // Using the second connection to subscribes to another 50 topics, IoT core limits subscriptions to 50 topics per connection.
        // if the session from first connection is not terminated, subscribe operations made by second connection will not succeed.
        for (int i = 0; i < numberOfTopics; i++) {
            client.subscribe(SubscribeRequest.builder().topic("B/" + i).callback((m) -> {
                cdl.countDown();
                logger.atInfo().kv("remaining", cdl.getCount()).log("Received 1 message from cloud.");
            }).build());
        }
        for (int i = 0; i < numberOfTopics; i++) {
            client.publish(PublishRequest.builder().topic("B/" + i).payload("What's up".getBytes(StandardCharsets.UTF_8))
                    .build()).get(5, TimeUnit.SECONDS);
            logger.atInfo().kv("total", i + 1).log("Added 1 message to spooler.");
        }
        assertTrue(cdl.await(1, TimeUnit.MINUTES), "All messages published and received");
    }

    @Test
    void GIVEN_mqttClient_WHEN_subscribe_interrupted_THEN_does_not_close_connection(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, InterruptedException.class);
        CountDownLatch interruptCdl = new CountDownLatch(1);

        Consumer<GreengrassLogMessage> logListener = m -> {
            if ("Connection purposefully interrupted".equals(m.getMessage())) {
                if (m.getContexts().get("clientId").endsWith("#2")) {
                    interruptCdl.countDown();
                }
            }
        };
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AutoCloseable l = createCloseableLogListener(logListener)) {
            int numberOfTopics = 100;
            CountDownLatch messagesCdl = new CountDownLatch(numberOfTopics);
            kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString());
            setDefaultRunWithUser(kernel);
            deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, TEST_REGION.toString(),
                    TES_ROLE_ALIAS_NAME, CERT_PATH);

            MqttClient client = kernel.getContext().get(MqttClient.class);
            Future<?> subscribeFuture = executorService.submit(() -> {
                for (int i = 0; i < numberOfTopics; i++) {
                    String topic = "B/" + i;
                    try {
                        client.subscribe(SubscribeRequest.builder().topic(topic).callback((m) -> {
                            messagesCdl.countDown();
                            logger.atInfo().kv("remaining", messagesCdl.getCount())
                                    .log("Received 1 message from cloud.");
                        }).build());
                    } catch (ExecutionException | InterruptedException | TimeoutException e) {
                        logger.atError().kv("topic", topic).cause(e).log("Error subscribing");
                    }
                }
            });
            assertTrue(interruptCdl.await(40, TimeUnit.SECONDS), "Connection should be interrupted for client 2");
            subscribeFuture.cancel(true);
            for (int i = 0; i < numberOfTopics; i++) {
                client.publish(PublishRequest.builder().topic("B/" + i).payload("What's up".getBytes(StandardCharsets.UTF_8))
                        .build()).get(5, TimeUnit.SECONDS);
                logger.atInfo().kv("total", i + 1).log("Added 1 message to spooler.");
            }
            assertTrue(messagesCdl.await(1, TimeUnit.MINUTES), "All messages published and received");
        } finally {
            executorService.shutdownNow();
        }
    }
}
