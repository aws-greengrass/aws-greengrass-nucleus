/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.mqttclient.v5.PubAck;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.SubscribeResponse;
import com.aws.greengrass.mqttclient.v5.UnsubscribeResponse;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

interface IndividualMqttClient extends Closeable {
    long getThrottlingWaitTimeMicros();

    boolean canAddNewSubscription();

    int subscriptionCount();

    boolean isConnectionClosable();

    boolean connected();

    String getClientId();

    int getClientIdNum();

    void closeOnShutdown();

    CompletableFuture<SubscribeResponse> subscribe(Subscribe subscribe);

    CompletableFuture<UnsubscribeResponse> unsubscribe(String topic);

    CompletableFuture<PubAck> publish(Publish publish);

    @Override
    void close();
}
