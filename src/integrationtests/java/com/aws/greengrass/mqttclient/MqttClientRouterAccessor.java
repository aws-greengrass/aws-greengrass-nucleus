/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.mqttclient.v5.Publish;

import static org.mockito.Mockito.mock;

/**
 * Test-only accessor that delivers an inbound message through {@link MqttClient}'s inbound router,
 * {@link MqttClient#getMessageHandlerForClient}, exactly as an arriving PUBLISH would.
 *
 * <p>The router and its {@link IndividualMqttClient} parameter are package-private, so this accessor lives in
 * {@code com.aws.greengrass.mqttclient} to reach them and exposes a public entry point for tests in other packages.
 */
public final class MqttClientRouterAccessor {

    private MqttClientRouterAccessor() {
    }

    /**
     * Deliver an inbound message through the real router, as an arriving PUBLISH would.
     *
     * @param mqttClient client whose router is exercised
     * @param message    inbound message to route
     */
    public static void routeInboundMessage(MqttClient mqttClient, Publish message) {
        mqttClient.getMessageHandlerForClient(mock(IndividualMqttClient.class)).accept(message);
    }
}
