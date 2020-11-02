/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public class CallbackEventManager {
    private final Set<MqttClientConnectionEvents> oneTimeCallbackEvents = new CopyOnWriteArraySet<>();
    private final Set<OnConnectCallback> onConnectCallbacks = new CopyOnWriteArraySet<>();
    private final AtomicBoolean hasCallBacked = new AtomicBoolean(false);

    public interface OnConnectCallback {
        void onConnect(boolean curSessionPresent);
    }

    /**
     *  A MqttClient may control multiple AwsIotMqttClients
     *  and each AwsIotMqttClients may have multiple callback events.
     * @param curSessionPresent is specific for each AwsIotMqttClient controlled by the MqttClient.
     *                          If false, mqtt Client do the callback actions. Otherwise, do nothing.
     *
     */
    public void runOnConnectionResumed(boolean curSessionPresent) {
        // This type of callback would only be triggered once by one of the AwsIotMqttClient.
        if (hasCallBacked.compareAndSet(false, true)) {
            for (MqttClientConnectionEvents callback : oneTimeCallbackEvents) {
                callback.onConnectionResumed(curSessionPresent);
            }
        }
    }

    /**
     * A MqttClient may control multiple AwsIotMqttClients and when the first AwsIotMqttClients
     * got connected, trigger Initial connect Event.
     * @param curSessionPresent current session present
     *
     */
    public void runOnInitialConnect(boolean curSessionPresent) {
        // This type of callback would only be triggered once by one of the AwsIotMqttClient.
        if (hasCallBacked.compareAndSet(false, true)) {
            for (OnConnectCallback callback : onConnectCallbacks) {
                callback.onConnect(curSessionPresent);
            }
        }
    }

    /**
     * To run method of OnConnectionInterrupted if the connections are dropped.
     * @param errorCode would shared by all the callbacks.
     *
     */
    public void runOnConnectionInterrupted(int errorCode) {
        if (hasCallBacked.compareAndSet(true, false)) {
            for (MqttClientConnectionEvents callback : oneTimeCallbackEvents) {
                callback.onConnectionInterrupted(errorCode);
            }
        }
    }

    /**
     * To add callback to the set of callBackEvents.
     * @param callback is an instance of MqttClientConnectionEvents.
     */
    public void addToCallbackEvents(MqttClientConnectionEvents callback) {
        oneTimeCallbackEvents.add(callback);
    }

    public void addToCallbackEvents(OnConnectCallback onConnect, MqttClientConnectionEvents callback) {
        onConnectCallbacks.add(onConnect);
        oneTimeCallbackEvents.add(callback);
    }

    /**
     * To check whether the oneTimeCallback has been done.
     * @return boolean.
     */
    public boolean hasCallbacked() {
        return hasCallBacked.get();
    }
}
