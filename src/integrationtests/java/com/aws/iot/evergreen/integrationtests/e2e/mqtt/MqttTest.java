/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.mqtt;

import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.PublishRequest;
import com.aws.iot.evergreen.mqtt.SubscribeRequest;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith(EGExtension.class)
@Tag("E2E")
public class MqttTest extends BaseE2ETestCase {
    public static final int NUM_MESSAGES = 50;
    private Kernel kernel;

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

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, GAMMA_REGION.toString());

        MqttClient client = kernel.getContext().get(MqttClient.class);
        CountDownLatch cdl = new CountDownLatch(NUM_MESSAGES);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback((m) -> {
            cdl.countDown();
        }).build());

        for (int i = 0; i < NUM_MESSAGES; i++) {
            client.publish(PublishRequest.builder().topic("A/B/C").payload("What's up".getBytes(StandardCharsets.UTF_8))
                    .build()).get(1, TimeUnit.SECONDS);
        }

        assertTrue(cdl.await(1, TimeUnit.MINUTES), "All messages published and received");
    }
}
