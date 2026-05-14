/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import java.util.concurrent.CompletableFuture;

/**
 * JNI bridge to the coreMQTT channel handler native library.
 * All native methods delegate to coremqtt_jni.c.
 */
final class CoreMqttNative {

    static {
        System.loadLibrary("coremqtt_jni");
    }

    private CoreMqttNative() {
    }

    /**
     * Create a new coreMQTT channel handler instance.
     *
     * @param keepAliveSec keep-alive interval in seconds
     * @param callback     callback handler for events (implements CoreMqttCallbackHandler)
     * @return native handle (pointer)
     */
    static native long create(int keepAliveSec, Object callback);

    /**
     * Destroy the native handler and free resources.
     */
    static native void destroy(long handle);

    /**
     * Initiate TCP+TLS connection and send MQTT CONNECT.
     *
     * @param handle          native handle
     * @param endpoint        IoT Core endpoint
     * @param port            port (typically 8883)
     * @param bootstrapHandle native handle to aws_client_bootstrap
     * @param tlsCtxHandle    native handle to aws_tls_ctx
     * @param clientId        MQTT client ID
     */
    static native void connect(long handle, String endpoint, int port,
                               long bootstrapHandle, long tlsCtxHandle, String clientId);

    /**
     * Send MQTT DISCONNECT and shut down the channel.
     */
    static native void disconnect(long handle);

    /**
     * Publish a message. Future is completed when PUBACK is received (QoS 1)
     * or immediately after send (QoS 0).
     */
    static native void publish(long handle, String topic, byte[] payload,
                               int qos, boolean retain, Object future);

    /**
     * Subscribe to a topic. Future is completed when SUBACK is received.
     */
    static native void subscribe(long handle, String topic, int qos, Object future);

    /**
     * Unsubscribe from a topic. Future is completed when UNSUBACK is received.
     */
    static native void unsubscribe(long handle, String topic, Object future);

    /**
     * Check if the MQTT connection is currently active.
     */
    static native boolean isConnected(long handle);
}
