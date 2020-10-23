/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.mqttclient;

import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
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
@ExtendWith(GGExtension.class)
@Tag("E2E")
class MqttTest extends BaseE2ETestCase {
    public static final int NUM_MESSAGES = 50;
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

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, GAMMA_REGION.toString(),
                TES_ROLE_ALIAS_NAME);

        MqttClient client = kernel.getContext().get(MqttClient.class);
        CountDownLatch cdl = new CountDownLatch(NUM_MESSAGES);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback((m) -> {
            cdl.countDown();
            logger.atInfo().kv("remaining", cdl.getCount()).log("Received 1 message from cloud.");
        }).build());

        for (int i = 0; i < NUM_MESSAGES; i++) {
            client.publish(PublishRequest.builder().topic("A/B/C").payload("What's up".getBytes(StandardCharsets.UTF_8))
                    .build()).get(5, TimeUnit.SECONDS);
            logger.atInfo().kv("total", i + 1).log("Published 1 message to cloud.");
        }

        assertTrue(cdl.await(1, TimeUnit.MINUTES), "All messages published and received");
    }
}
