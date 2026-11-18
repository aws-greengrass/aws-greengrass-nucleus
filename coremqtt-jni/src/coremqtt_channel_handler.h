/*
 * coreMQTT Channel Handler Adapter for aws-c-io pipeline.
 * Replaces aws-c-mqtt as the MQTT protocol handler in the channel.
 */
#ifndef COREMQTT_CHANNEL_HANDLER_H
#define COREMQTT_CHANNEL_HANDLER_H

#include <aws/common/byte_buf.h>
#include <aws/common/hash_table.h>
#include <aws/common/task_scheduler.h>
#include <aws/io/channel.h>
#include <aws/io/channel_bootstrap.h>
#include <aws/io/event_loop.h>
#include <aws/io/host_resolver.h>
#include <jni.h>

#include "core_mqtt.h"
#include "core_mqtt_serializer.h"

/* Network buffer size: 130KB (IoT Core max payload is 128KB + headers/properties) */
#define COREMQTT_NETWORK_BUFFER_SIZE    ( 130U * 1024U )

/* Max in-flight QoS 1 publishes tracked */
#define COREMQTT_MAX_OUTGOING_PUBLISHES ( 100U )
#define COREMQTT_MAX_INCOMING_PUBLISHES ( 100U )

/* Receive ring buffer size */
#define COREMQTT_RECV_BUFFER_SIZE       ( 256U * 1024U )

/* Ack properties buffer size */
#define COREMQTT_ACK_PROPS_BUF_SIZE     ( 1024U )

struct coremqtt_channel_handler {
    /* aws-c-io channel handler base */
    struct aws_channel_handler base;
    struct aws_channel_slot *slot;
    struct aws_event_loop *loop;
    struct aws_allocator *allocator;

    /* coreMQTT context and buffers */
    MQTTContext_t mqtt_ctx;
    uint8_t network_buffer[COREMQTT_NETWORK_BUFFER_SIZE];
    MQTTPubAckInfo_t outgoing_records[COREMQTT_MAX_OUTGOING_PUBLISHES];
    MQTTPubAckInfo_t incoming_records[COREMQTT_MAX_INCOMING_PUBLISHES];
    uint8_t ack_props_buffer[COREMQTT_ACK_PROPS_BUF_SIZE];

    /* Receive ring buffer: filled by channel read, drained by TransportRecv */
    uint8_t recv_buffer_storage[COREMQTT_RECV_BUFFER_SIZE];
    size_t recv_write_pos;
    size_t recv_read_pos;

    /* Pending ACK tracking: packetId -> callback user_data */
    struct aws_hash_table pending_acks;

    /* Keep-alive timer */
    struct aws_task keepalive_task;
    bool keepalive_scheduled;

    /* Connection state */
    bool is_connected;
    bool waiting_for_connack;
    uint16_t keep_alive_sec;
    char *client_id;

    /* Our own CRT infrastructure (separate from Java CRT) */
    struct aws_event_loop_group *event_loop_group;
    struct aws_host_resolver *host_resolver;
    struct aws_client_bootstrap *bootstrap;

    /* JNI callback references */
    JavaVM *jvm;
    jobject java_callback;   /* global ref to CoreMqttCallbackHandler */
    jmethodID on_publish_received_mid;
    jmethodID on_connection_success_mid;
    jmethodID on_connection_failure_mid;
    jmethodID on_disconnection_mid;
    jmethodID on_ack_received_mid;
};

/* Create a new channel handler. Call before initiating connection. */
struct coremqtt_channel_handler *coremqtt_channel_handler_new(
    struct aws_allocator *allocator,
    JavaVM *jvm,
    jobject java_callback,
    uint16_t keep_alive_sec);

/* Destroy and free. */
void coremqtt_channel_handler_destroy(struct coremqtt_channel_handler *handler);

/* Submit a publish operation (called from JNI, schedules event loop task). */
void coremqtt_publish(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    const uint8_t *payload,
    size_t payload_len,
    MQTTQoS_t qos,
    bool retain,
    jobject java_future);

/* Submit a subscribe operation. */
void coremqtt_subscribe(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    MQTTQoS_t qos,
    jobject java_future);

/* Submit an unsubscribe operation. */
void coremqtt_unsubscribe(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    jobject java_future);

/* Channel setup callback - to be used with aws_client_bootstrap_new_socket_channel. */
void coremqtt_on_channel_setup(
    struct aws_client_bootstrap *bootstrap,
    int error_code,
    struct aws_channel *channel,
    void *user_data);

/* Channel shutdown callback. */
void coremqtt_on_channel_shutdown(
    struct aws_client_bootstrap *bootstrap,
    int error_code,
    struct aws_channel *channel,
    void *user_data);

#endif /* COREMQTT_CHANNEL_HANDLER_H */
