/*
 * coreMQTT Channel Handler Adapter.
 * Plugs coreMQTT into the aws-c-io channel pipeline, replacing aws-c-mqtt.
 */
#include "coremqtt_channel_handler.h"

#include <aws/common/allocator.h>
#include <aws/common/clock.h>
#include <aws/io/channel.h>
#include <aws/io/event_loop.h>
#include <aws/io/socket.h>
#include <string.h>

/* ─── Forward declarations ─────────────────────────────────────────────── */

static int s_process_read_message(
    struct aws_channel_handler *handler,
    struct aws_channel_slot *slot,
    struct aws_io_message *message);

static void s_schedule_keepalive(struct coremqtt_channel_handler *h);

static int s_shutdown(
    struct aws_channel_handler *handler,
    struct aws_channel_slot *slot,
    enum aws_channel_direction dir,
    int error_code,
    bool free_scarce_resources_immediately);

static size_t s_initial_window_size(struct aws_channel_handler *handler);
static size_t s_message_overhead(struct aws_channel_handler *handler);
static void s_destroy_handler(struct aws_channel_handler *handler);

static struct aws_channel_handler_vtable s_vtable = {
    .process_read_message = s_process_read_message,
    .process_write_message = NULL, /* We don't receive write messages from upstream */
    .increment_read_window = NULL,
    .shutdown = s_shutdown,
    .initial_window_size = s_initial_window_size,
    .message_overhead = s_message_overhead,
    .destroy = s_destroy_handler,
};

/* ─── Time function for coreMQTT ──────────────────────────────────────── */

static uint32_t s_get_time_ms(void) {
    uint64_t now_ns = 0;
    aws_high_res_clock_get_ticks(&now_ns);
    return (uint32_t)(now_ns / 1000000ULL);
}

/* ─── Transport Interface: recv (reads from ring buffer) ───────────────── */

static int32_t s_transport_recv(
    NetworkContext_t *pNetworkContext,
    void *pBuffer,
    size_t bytesToRecv) {

    struct coremqtt_channel_handler *h = (struct coremqtt_channel_handler *)pNetworkContext;

    size_t available = h->recv_write_pos - h->recv_read_pos;
    if (available == 0) {
        return 0; /* No data available - coreMQTT will return MQTTNoDataAvailable */
    }

    size_t to_copy = (available < bytesToRecv) ? available : bytesToRecv;
    memcpy(pBuffer, h->recv_buffer_storage + h->recv_read_pos, to_copy);
    h->recv_read_pos += to_copy;

    /* Compact buffer when fully consumed */
    if (h->recv_read_pos == h->recv_write_pos) {
        h->recv_read_pos = 0;
        h->recv_write_pos = 0;
    }

    return (int32_t)to_copy;
}

/* ─── Transport Interface: send (writes to channel pipeline) ───────────── */

static int32_t s_transport_send(
    NetworkContext_t *pNetworkContext,
    const void *pBuffer,
    size_t bytesToSend) {

    struct coremqtt_channel_handler *h = (struct coremqtt_channel_handler *)pNetworkContext;


    if (h->slot == NULL || h->slot->channel == NULL) {
        return -1;
    }

    struct aws_io_message *msg = aws_channel_acquire_message_from_pool(
        h->slot->channel, AWS_IO_MESSAGE_APPLICATION_DATA, bytesToSend);
    if (msg == NULL) {
        return -1;
    }

    struct aws_byte_cursor data = aws_byte_cursor_from_array(pBuffer, bytesToSend);
    if (!aws_byte_buf_write_from_whole_cursor(&msg->message_data, data)) {
        aws_mem_release(msg->allocator, msg);
        return -1;
    }

    if (aws_channel_slot_send_message(h->slot, msg, AWS_CHANNEL_DIR_WRITE)) {
        aws_mem_release(msg->allocator, msg);
        return -1;
    }

    return (int32_t)bytesToSend;
}

/* ─── Transport Interface: writev (sends all vectors in one channel message) ── */

static int32_t s_transport_writev(
    NetworkContext_t *pNetworkContext,
    TransportOutVector_t *pIoVec,
    size_t ioVecCount) {

    struct coremqtt_channel_handler *h = (struct coremqtt_channel_handler *)pNetworkContext;

    if (h->slot == NULL || h->slot->channel == NULL) {
        return -1;
    }

    /* Calculate total size */
    size_t total = 0;
    for (size_t i = 0; i < ioVecCount; i++) {
        total += pIoVec[i].iov_len;
    }

    struct aws_io_message *msg = aws_channel_acquire_message_from_pool(
        h->slot->channel, AWS_IO_MESSAGE_APPLICATION_DATA, total);
    if (msg == NULL) {
        return -1;
    }

    /* Copy all vectors into one message */
    for (size_t i = 0; i < ioVecCount; i++) {
        struct aws_byte_cursor data = aws_byte_cursor_from_array(pIoVec[i].iov_base, pIoVec[i].iov_len);
        aws_byte_buf_write_from_whole_cursor(&msg->message_data, data);
    }


    if (aws_channel_slot_send_message(h->slot, msg, AWS_CHANNEL_DIR_WRITE)) {
        aws_mem_release(msg->allocator, msg);
        return -1;
    }

    return (int32_t)total;
}

/* ─── Channel handler vtable implementations ──────────────────────────── */

static int s_process_read_message(
    struct aws_channel_handler *handler,
    struct aws_channel_slot *slot,
    struct aws_io_message *message) {

    struct coremqtt_channel_handler *h = handler->impl;


    /* Append decrypted bytes to recv ring buffer */
    size_t incoming_len = message->message_data.len;
    size_t space = COREMQTT_RECV_BUFFER_SIZE - h->recv_write_pos;

    if (incoming_len > space) {
        /* Compact first */
        if (h->recv_read_pos > 0) {
            size_t unread = h->recv_write_pos - h->recv_read_pos;
            memmove(h->recv_buffer_storage, h->recv_buffer_storage + h->recv_read_pos, unread);
            h->recv_write_pos = unread;
            h->recv_read_pos = 0;
            space = COREMQTT_RECV_BUFFER_SIZE - h->recv_write_pos;
        }
        if (incoming_len > space) {
            aws_mem_release(message->allocator, message);
            return AWS_OP_ERR;
        }
    }

    memcpy(h->recv_buffer_storage + h->recv_write_pos, message->message_data.buffer, incoming_len);
    h->recv_write_pos += incoming_len;

    aws_channel_slot_increment_read_window(slot, incoming_len);
    aws_mem_release(message->allocator, message);

    /* If waiting for CONNACK, check if we got it */
    if (h->waiting_for_connack) {
        /* CONNACK is: type byte (0x20) + remaining length + payload */
        size_t available = h->recv_write_pos - h->recv_read_pos;
        if (available >= 2) {
            uint8_t *buf = h->recv_buffer_storage + h->recv_read_pos;
            if ((buf[0] & 0xF0) == MQTT_PACKET_TYPE_CONNACK) {
                /* Parse remaining length to know full packet size */
                uint32_t rem_len = buf[1]; /* simplified: assumes 1-byte remaining length */
                size_t packet_size = 2 + rem_len;
                if (available >= packet_size) {
                    /* We have the full CONNACK - consume it from buffer */
                    h->recv_read_pos += packet_size;
                    if (h->recv_read_pos == h->recv_write_pos) {
                        h->recv_read_pos = 0;
                        h->recv_write_pos = 0;
                    }

                    h->waiting_for_connack = false;
                    h->is_connected = true;
                    h->mqtt_ctx.connectStatus = MQTTConnected;
                    h->mqtt_ctx.index = 0; /* Reset parser state for clean ProcessLoop */
                    h->mqtt_ctx.keepAliveIntervalSec = h->keep_alive_sec;
                    h->mqtt_ctx.connectionProperties.serverMaxPacketSize = 268435460U; /* MQTT max */
                    h->mqtt_ctx.connectionProperties.maxPacketSize = COREMQTT_NETWORK_BUFFER_SIZE;
                    s_schedule_keepalive(h);

                    /* Notify Java */
                    if (h->java_callback != NULL) {
                        JNIEnv *env = NULL;
                        (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                        if (env) {
                            (*env)->CallVoidMethod(env, h->java_callback, h->on_connection_success_mid,
                                                  (jboolean)false);
                        }
                    }
                }
            } else if ((buf[0] & 0xF0) == MQTT_PACKET_TYPE_DISCONNECT) {
                /* Server sent DISCONNECT instead of CONNACK */
                h->waiting_for_connack = false;
                if (h->java_callback != NULL) {
                    JNIEnv *env = NULL;
                    (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                    if (env) {
                        (*env)->CallVoidMethod(env, h->java_callback, h->on_connection_failure_mid, (jint)142);
                    }
                }
                aws_channel_shutdown(slot->channel, AWS_ERROR_INVALID_STATE);
            } else {
                /* Not a CONNACK - server sent something unexpected, disconnect */
                h->waiting_for_connack = false;
                aws_channel_shutdown(slot->channel, AWS_ERROR_INVALID_STATE);
            }
        }
        return AWS_OP_SUCCESS;
    }

    /* Normal operation: trigger coreMQTT to process the data */
    if (!h->is_connected) {
        return AWS_OP_SUCCESS; /* Ignore data on disconnected/failed connections */
    }
    MQTTStatus_t mqtt_status = MQTT_ProcessLoop(&h->mqtt_ctx);
    if (mqtt_status == MQTTBadResponse) {
        /* Reset buffer and continue */
        h->recv_read_pos = 0;
        h->recv_write_pos = 0;
        h->mqtt_ctx.index = 0;
    }

    return AWS_OP_SUCCESS;
}

static int s_shutdown(
    struct aws_channel_handler *handler,
    struct aws_channel_slot *slot,
    enum aws_channel_direction dir,
    int error_code,
    bool free_scarce_resources_immediately) {

    (void)handler;
    return aws_channel_slot_on_handler_shutdown_complete(slot, dir, error_code, free_scarce_resources_immediately);
}

static size_t s_initial_window_size(struct aws_channel_handler *handler) {
    (void)handler;
    return SIZE_MAX;
}

static size_t s_message_overhead(struct aws_channel_handler *handler) {
    (void)handler;
    return 0;
}

static void s_destroy_handler(struct aws_channel_handler *handler) {
    (void)handler;
    /* Actual cleanup done in coremqtt_channel_handler_destroy */
}

/* ─── Pending ACK helpers ─────────────────────────────────────────────── */

struct pending_ack_data {
    jobject java_future; /* global ref */
    JavaVM *jvm;
};

static void s_pending_ack_destroy(void *value) {
    /* Note: java_future global ref must be deleted by caller before removing */
    struct pending_ack_data *data = value;
    /* We don't free here - freed after completing the future */
    (void)data;
}

static void s_complete_java_future(JavaVM *jvm, jobject future, int reason_code, bool success) {
    JNIEnv *env = NULL;
    (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
    if (env == NULL) return;

    jclass future_class = (*env)->GetObjectClass(env, future);
    if (success) {
        jmethodID complete_mid = (*env)->GetMethodID(env, future_class, "complete", "(Ljava/lang/Object;)Z");
        /* Complete with Integer reason code */
        jclass int_class = (*env)->FindClass(env, "java/lang/Integer");
        jmethodID int_valueof = (*env)->GetStaticMethodID(env, int_class, "valueOf", "(I)Ljava/lang/Integer;");
        jobject boxed = (*env)->CallStaticObjectMethod(env, int_class, int_valueof, reason_code);
        (*env)->CallBooleanMethod(env, future, complete_mid, boxed);
        (*env)->DeleteLocalRef(env, boxed);
        (*env)->DeleteLocalRef(env, int_class);
    } else {
        jmethodID fail_mid = (*env)->GetMethodID(env, future_class, "completeExceptionally", "(Ljava/lang/Throwable;)Z");
        jclass exc_class = (*env)->FindClass(env, "java/lang/RuntimeException");
        jmethodID exc_init = (*env)->GetMethodID(env, exc_class, "<init>", "(Ljava/lang/String;)V");
        jstring msg = (*env)->NewStringUTF(env, "MQTT operation failed");
        jobject exc = (*env)->NewObject(env, exc_class, exc_init, msg);
        (*env)->CallBooleanMethod(env, future, fail_mid, exc);
        (*env)->DeleteLocalRef(env, exc);
        (*env)->DeleteLocalRef(env, msg);
        (*env)->DeleteLocalRef(env, exc_class);
    }
    (*env)->DeleteLocalRef(env, future_class);
}

/* ─── coreMQTT Event Callback ─────────────────────────────────────────── */

static bool s_mqtt_event_callback(
    MQTTContext_t *pContext,
    MQTTPacketInfo_t *pPacketInfo,
    MQTTDeserializedInfo_t *pDeserializedInfo,
    MQTTSuccessFailReasonCode_t *pReasonCode,
    MQTTPropBuilder_t *pSendPropsBuffer,
    MQTTPropBuilder_t *pGetPropsBuffer) {

    (void)pReasonCode;
    (void)pSendPropsBuffer;
    (void)pGetPropsBuffer;

    struct coremqtt_channel_handler *h =
        (struct coremqtt_channel_handler *)pContext->transportInterface.pNetworkContext;

    uint16_t packet_id = pDeserializedInfo->packetIdentifier;
    uint8_t packet_type = pPacketInfo->type & 0xF0U;


    switch (packet_type) {
        case MQTT_PACKET_TYPE_PUBACK:
        case MQTT_PACKET_TYPE_SUBACK:
        case MQTT_PACKET_TYPE_UNSUBACK: {
            /* Look up and complete the pending future */
            struct aws_hash_element *elem = NULL;
            uint64_t key = (uint64_t)packet_id;
            aws_hash_table_find(&h->pending_acks, (void *)key, &elem);
            if (elem != NULL && elem->value != NULL) {
                struct pending_ack_data *ack_data = elem->value;
                int rc = 0;
                if (pDeserializedInfo->pReasonCode != NULL) {
                    rc = (int)pDeserializedInfo->pReasonCode->reasonCode[0];
                }
                s_complete_java_future(h->jvm, ack_data->java_future, rc, true);
                JNIEnv *env = NULL;
                (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                if (env) {
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionDescribe(env);
                        (*env)->ExceptionClear(env);
                    }
                    (*env)->DeleteGlobalRef(env, ack_data->java_future);
                }
                aws_mem_release(h->allocator, ack_data);
                aws_hash_table_remove(&h->pending_acks, (void *)key, NULL, NULL);
            }
            break;
        }

        case MQTT_PACKET_TYPE_PUBLISH: {
            /* Incoming publish → JNI upcall to Java */
            if (pDeserializedInfo->pPublishInfo != NULL && h->java_callback != NULL) {
                MQTTPublishInfo_t *pub = pDeserializedInfo->pPublishInfo;
                JNIEnv *env = NULL;
                (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                if (env != NULL) {
                    jstring jtopic = (*env)->NewStringUTF(env, pub->pTopicName);
                    jbyteArray jpayload = (*env)->NewByteArray(env, (jsize)pub->payloadLength);
                    (*env)->SetByteArrayRegion(env, jpayload, 0, (jsize)pub->payloadLength,
                                              (const jbyte *)pub->pPayload);
                    (*env)->CallVoidMethod(env, h->java_callback, h->on_publish_received_mid,
                                          jtopic, jpayload, (jint)pub->qos, (jboolean)pub->retain);
                    (*env)->DeleteLocalRef(env, jtopic);
                    (*env)->DeleteLocalRef(env, jpayload);
                }
            }
            break;
        }

        case MQTT_PACKET_TYPE_DISCONNECT: {
            h->is_connected = false;
            if (h->java_callback != NULL) {
                JNIEnv *env = NULL;
                (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                if (env != NULL) {
                    (*env)->CallVoidMethod(env, h->java_callback, h->on_disconnection_mid, (jint)0);
                }
            }
            break;
        }

        default:
            break;
    }

    return true;
}

/* ─── Keep-alive timer task ───────────────────────────────────────────── */

static void s_keepalive_task_fn(struct aws_task *task, void *arg, enum aws_task_status status) {
    (void)task;
    struct coremqtt_channel_handler *h = arg;

    if (status != AWS_TASK_STATUS_RUN_READY || !h->is_connected) {
        h->keepalive_scheduled = false;
        return;
    }

    /* ProcessLoop with no new data: checks timers, sends PINGREQ if needed */
    MQTTStatus_t mqtt_status = MQTT_ProcessLoop(&h->mqtt_ctx);

    if (mqtt_status == MQTTKeepAliveTimeout) {
        h->is_connected = false;
        if (h->slot && h->slot->channel) {
            aws_channel_shutdown(h->slot->channel, AWS_ERROR_INVALID_STATE);
        }
        h->keepalive_scheduled = false;
        return;
    }

    /* Reschedule: run at half the keep-alive interval */
    uint64_t now_ns = 0;
    aws_high_res_clock_get_ticks(&now_ns);
    uint64_t interval_ns = (uint64_t)h->keep_alive_sec * 500000000ULL; /* half interval */
    if (interval_ns == 0) interval_ns = 15000000000ULL; /* default 15s if keepalive is 0 */
    aws_event_loop_schedule_task_future(h->loop, &h->keepalive_task, now_ns + interval_ns);
}

static void s_schedule_keepalive(struct coremqtt_channel_handler *h) {
    if (h->keepalive_scheduled) return;
    h->keepalive_scheduled = true;
    aws_task_init(&h->keepalive_task, s_keepalive_task_fn, h, "CoreMqttKeepAlive");
    uint64_t now_ns = 0;
    aws_high_res_clock_get_ticks(&now_ns);
    uint64_t interval_ns = (uint64_t)h->keep_alive_sec * 500000000ULL;
    if (interval_ns == 0) interval_ns = 15000000000ULL;
    aws_event_loop_schedule_task_future(h->loop, &h->keepalive_task, now_ns + interval_ns);
}

/* ─── Operation task data structures ──────────────────────────────────── */

struct publish_task_data {
    struct aws_task task;
    struct coremqtt_channel_handler *handler;
    char *topic;
    size_t topic_len;
    uint8_t *payload;
    size_t payload_len;
    MQTTQoS_t qos;
    bool retain;
    jobject java_future; /* global ref */
};

struct subscribe_task_data {
    struct aws_task task;
    struct coremqtt_channel_handler *handler;
    char *topic;
    size_t topic_len;
    MQTTQoS_t qos;
    jobject java_future; /* global ref */
};

struct unsubscribe_task_data {
    struct aws_task task;
    struct coremqtt_channel_handler *handler;
    char *topic;
    size_t topic_len;
    jobject java_future; /* global ref */
};

/* ─── Publish task (runs on event loop thread) ────────────────────────── */

static void s_publish_task_fn(struct aws_task *task, void *arg, enum aws_task_status status) {
    (void)task;
    struct publish_task_data *data = arg;
    struct coremqtt_channel_handler *h = data->handler;

    if (status != AWS_TASK_STATUS_RUN_READY || !h->is_connected) {
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        goto cleanup;
    }

    MQTTPublishInfo_t pub_info = {
        .qos = data->qos,
        .retain = data->retain,
        .dup = false,
        .pTopicName = data->topic,
        .topicNameLength = data->topic_len,
        .pPayload = data->payload,
        .payloadLength = data->payload_len,
    };

    uint16_t packet_id = 0;
    if (data->qos > MQTTQoS0) {
        packet_id = MQTT_GetPacketId(&h->mqtt_ctx);
        /* Store pending ACK */
        struct pending_ack_data *ack_data = aws_mem_calloc(h->allocator, 1, sizeof(struct pending_ack_data));
        ack_data->java_future = data->java_future;
        ack_data->jvm = h->jvm;
        uint64_t key = (uint64_t)packet_id;
        aws_hash_table_put(&h->pending_acks, (void *)key, ack_data, NULL);
    }

    MQTTStatus_t result = MQTT_Publish(&h->mqtt_ctx, &pub_info, packet_id, NULL);

    if (result != MQTTSuccess) {
        if (packet_id != 0) {
            uint64_t key = (uint64_t)packet_id;
            struct aws_hash_element *elem = NULL;
            aws_hash_table_find(&h->pending_acks, (void *)key, &elem);
            if (elem) {
                aws_mem_release(h->allocator, elem->value);
                aws_hash_table_remove(&h->pending_acks, (void *)key, NULL, NULL);
            }
        }
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        goto cleanup;
    }

    /* QoS 0: complete immediately (no PUBACK expected) */
    if (data->qos == MQTTQoS0) {
        s_complete_java_future(h->jvm, data->java_future, 0, true);
    }
    /* QoS 1: future will be completed when PUBACK arrives in event callback */

cleanup:
    if (data->qos == MQTTQoS0 || result != MQTTSuccess) {
        JNIEnv *env = NULL;
        (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
        if (env) (*env)->DeleteGlobalRef(env, data->java_future);
    }
    aws_mem_release(h->allocator, data->topic);
    aws_mem_release(h->allocator, data->payload);
    aws_mem_release(h->allocator, data);
}

/* ─── Subscribe task (runs on event loop thread) ──────────────────────── */

static void s_subscribe_task_fn(struct aws_task *task, void *arg, enum aws_task_status status) {
    (void)task;
    struct subscribe_task_data *data = arg;
    struct coremqtt_channel_handler *h = data->handler;

    if (status != AWS_TASK_STATUS_RUN_READY || !h->is_connected) {
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        goto cleanup;
    }

    MQTTSubscribeInfo_t sub_info = {
        .qos = data->qos,
        .pTopicFilter = data->topic,
        .topicFilterLength = data->topic_len,
    };

    uint16_t packet_id = MQTT_GetPacketId(&h->mqtt_ctx);

    /* Store pending ACK */
    struct pending_ack_data *ack_data = aws_mem_calloc(h->allocator, 1, sizeof(struct pending_ack_data));
    ack_data->java_future = data->java_future;
    ack_data->jvm = h->jvm;
    uint64_t key = (uint64_t)packet_id;
    aws_hash_table_put(&h->pending_acks, (void *)key, ack_data, NULL);

    MQTTStatus_t result = MQTT_Subscribe(&h->mqtt_ctx, &sub_info, 1, packet_id, NULL);

    if (result != MQTTSuccess) {
        aws_mem_release(h->allocator, ack_data);
        aws_hash_table_remove(&h->pending_acks, (void *)key, NULL, NULL);
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        JNIEnv *env = NULL;
        (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
        if (env) (*env)->DeleteGlobalRef(env, data->java_future);
    }
    /* Otherwise future completed when SUBACK arrives */

cleanup:
    aws_mem_release(h->allocator, data->topic);
    aws_mem_release(h->allocator, data);
}

/* ─── Unsubscribe task (runs on event loop thread) ────────────────────── */

static void s_unsubscribe_task_fn(struct aws_task *task, void *arg, enum aws_task_status status) {
    (void)task;
    struct unsubscribe_task_data *data = arg;
    struct coremqtt_channel_handler *h = data->handler;

    if (status != AWS_TASK_STATUS_RUN_READY || !h->is_connected) {
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        goto cleanup;
    }

    MQTTSubscribeInfo_t unsub_info = {
        .pTopicFilter = data->topic,
        .topicFilterLength = data->topic_len,
    };

    uint16_t packet_id = MQTT_GetPacketId(&h->mqtt_ctx);

    struct pending_ack_data *ack_data = aws_mem_calloc(h->allocator, 1, sizeof(struct pending_ack_data));
    ack_data->java_future = data->java_future;
    ack_data->jvm = h->jvm;
    uint64_t key = (uint64_t)packet_id;
    aws_hash_table_put(&h->pending_acks, (void *)key, ack_data, NULL);

    MQTTStatus_t result = MQTT_Unsubscribe(&h->mqtt_ctx, &unsub_info, 1, packet_id, NULL);

    if (result != MQTTSuccess) {
        aws_mem_release(h->allocator, ack_data);
        aws_hash_table_remove(&h->pending_acks, (void *)key, NULL, NULL);
        s_complete_java_future(h->jvm, data->java_future, 0, false);
        JNIEnv *env = NULL;
        (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
        if (env) (*env)->DeleteGlobalRef(env, data->java_future);
    }

cleanup:
    aws_mem_release(h->allocator, data->topic);
    aws_mem_release(h->allocator, data);
}

/* ─── Public API: submit operations (called from JNI thread) ──────────── */

void coremqtt_publish(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    const uint8_t *payload,
    size_t payload_len,
    MQTTQoS_t qos,
    bool retain,
    jobject java_future) {

    struct publish_task_data *data = aws_mem_calloc(handler->allocator, 1, sizeof(struct publish_task_data));
    data->handler = handler;
    data->topic = aws_mem_calloc(handler->allocator, 1, topic_len + 1);
    memcpy(data->topic, topic, topic_len);
    data->topic[topic_len] = '\0';
    data->topic_len = topic_len;
    if (payload_len > 0) {
        data->payload = aws_mem_calloc(handler->allocator, 1, payload_len);
        memcpy(data->payload, payload, payload_len);
    }
    data->payload_len = payload_len;
    data->qos = qos;
    data->retain = retain;
    data->java_future = java_future; /* already a global ref */

    aws_task_init(&data->task, s_publish_task_fn, data, "CoreMqttPublish");
    aws_event_loop_schedule_task_now(handler->loop, &data->task);
}

void coremqtt_subscribe(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    MQTTQoS_t qos,
    jobject java_future) {

    struct subscribe_task_data *data = aws_mem_calloc(handler->allocator, 1, sizeof(struct subscribe_task_data));
    data->handler = handler;
    data->topic = aws_mem_calloc(handler->allocator, 1, topic_len + 1);
    memcpy(data->topic, topic, topic_len);
    data->topic[topic_len] = '\0';
    data->topic_len = topic_len;
    data->qos = qos;
    data->java_future = java_future;

    aws_task_init(&data->task, s_subscribe_task_fn, data, "CoreMqttSubscribe");
    aws_event_loop_schedule_task_now(handler->loop, &data->task);
}

void coremqtt_unsubscribe(
    struct coremqtt_channel_handler *handler,
    const char *topic,
    size_t topic_len,
    jobject java_future) {

    struct unsubscribe_task_data *data = aws_mem_calloc(handler->allocator, 1, sizeof(struct unsubscribe_task_data));
    data->handler = handler;
    data->topic = aws_mem_calloc(handler->allocator, 1, topic_len + 1);
    memcpy(data->topic, topic, topic_len);
    data->topic[topic_len] = '\0';
    data->topic_len = topic_len;
    data->java_future = java_future;

    aws_task_init(&data->task, s_unsubscribe_task_fn, data, "CoreMqttUnsubscribe");
    aws_event_loop_schedule_task_now(handler->loop, &data->task);
}

/* ─── Channel setup/shutdown callbacks ────────────────────────────────── */

void coremqtt_on_channel_setup(
    struct aws_client_bootstrap *bootstrap,
    int error_code,
    struct aws_channel *channel,
    void *user_data) {

    (void)bootstrap;
    struct coremqtt_channel_handler *h = user_data;

    /* Reset state from any previous connection attempt */
    h->mqtt_ctx.index = 0;
    h->mqtt_ctx.connectStatus = MQTTNotConnected;
    memset(h->network_buffer, 0, COREMQTT_NETWORK_BUFFER_SIZE);
    h->recv_read_pos = 0;
    h->recv_write_pos = 0;

    if (error_code != 0 || channel == NULL) {
        if (h->java_callback != NULL) {
            JNIEnv *env = NULL;
            (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
            if (env) {
                (*env)->CallVoidMethod(env, h->java_callback, h->on_connection_failure_mid, (jint)error_code);
            }
        }
        return;
    }

    /* Insert ourselves at the end of the channel pipeline (after TLS) */
    h->slot = aws_channel_slot_new(channel);
    aws_channel_slot_insert_end(channel, h->slot);
    aws_channel_slot_set_handler(h->slot, &h->base);
    h->loop = aws_channel_get_event_loop(channel);


    /* Send MQTT CONNECT packet using serializer (non-blocking, no waiting for CONNACK) */
    MQTTConnectInfo_t connect_info = {
        .cleanSession = false,
        .keepAliveSeconds = h->keep_alive_sec,
        .pClientIdentifier = h->client_id,
        .clientIdentifierLength = h->client_id ? strlen(h->client_id) : 0,
    };

    /* Get packet size */
    size_t remaining_length = 0;
    size_t packet_size = 0;
    MQTTStatus_t status = MQTT_GetConnectPacketSize(&connect_info, NULL, NULL, NULL,
                                                     &remaining_length, &packet_size);

    if (status == MQTTSuccess) {
        /* Serialize into a temporary buffer */
        uint8_t connect_buf[256];
        MQTTFixedBuffer_t fixed_buf = { .pBuffer = connect_buf, .size = sizeof(connect_buf) };
        status = MQTT_SerializeConnect(&connect_info, NULL, NULL, NULL,
                                        remaining_length, &fixed_buf);

        if (status == MQTTSuccess) {
            /* Send via transport */
            int32_t sent = s_transport_send((NetworkContext_t *)h, connect_buf, packet_size);

            if (sent > 0) {
                /* CONNECT sent - wait for CONNACK in s_process_read_message */
                h->waiting_for_connack = true;
            } else {
                if (h->java_callback != NULL) {
                    JNIEnv *env = NULL;
                    (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
                    if (env) {
                        (*env)->CallVoidMethod(env, h->java_callback, h->on_connection_failure_mid, (jint)4);
                    }
                }
                aws_channel_shutdown(channel, AWS_ERROR_INVALID_STATE);
            }
        }
    } else {
        if (h->java_callback != NULL) {
            JNIEnv *env = NULL;
            (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
            if (env) {
                (*env)->CallVoidMethod(env, h->java_callback, h->on_connection_failure_mid, (jint)status);
            }
        }
        aws_channel_shutdown(channel, AWS_ERROR_INVALID_STATE);
    }
}

void coremqtt_on_channel_shutdown(
    struct aws_client_bootstrap *bootstrap,
    int error_code,
    struct aws_channel *channel,
    void *user_data) {

    (void)bootstrap;
    (void)channel;
    struct coremqtt_channel_handler *h = user_data;

    h->is_connected = false;
    h->slot = NULL;
    h->keepalive_scheduled = false;

    if (h->java_callback != NULL) {
        JNIEnv *env = NULL;
        (*h->jvm)->AttachCurrentThread(h->jvm, (void **)&env, NULL);
        if (env) {
            (*env)->CallVoidMethod(env, h->java_callback, h->on_disconnection_mid, (jint)error_code);
        }
    }
}

/* ─── Create / Destroy ────────────────────────────────────────────────── */

static uint64_t s_hash_uint64(const void *key) {
    return (uint64_t)key;
}

static bool s_eq_uint64(const void *a, const void *b) {
    return a == b;
}

struct coremqtt_channel_handler *coremqtt_channel_handler_new(
    struct aws_allocator *allocator,
    JavaVM *jvm,
    jobject java_callback,
    uint16_t keep_alive_sec) {

    struct coremqtt_channel_handler *h = aws_mem_calloc(allocator, 1, sizeof(struct coremqtt_channel_handler));
    h->allocator = allocator;
    h->jvm = jvm;
    h->java_callback = java_callback; /* already a global ref */
    h->keep_alive_sec = keep_alive_sec;

    /* Set up channel handler vtable */
    h->base.vtable = &s_vtable;
    h->base.alloc = allocator;
    h->base.impl = h;

    /* Initialize coreMQTT */
    TransportInterface_t transport = {
        .recv = s_transport_recv,
        .send = s_transport_send,
        .writev = s_transport_writev,
        .pNetworkContext = (NetworkContext_t *)h,
    };

    MQTTFixedBuffer_t network_buf = {
        .pBuffer = h->network_buffer,
        .size = COREMQTT_NETWORK_BUFFER_SIZE,
    };

    MQTT_Init(&h->mqtt_ctx, &transport, s_get_time_ms, s_mqtt_event_callback, &network_buf);
    MQTT_InitStatefulQoS(&h->mqtt_ctx,
                         h->outgoing_records, COREMQTT_MAX_OUTGOING_PUBLISHES,
                         h->incoming_records, COREMQTT_MAX_INCOMING_PUBLISHES,
                         h->ack_props_buffer, COREMQTT_ACK_PROPS_BUF_SIZE);

    /* Initialize pending ACK hash table */
    aws_hash_table_init(&h->pending_acks, allocator, 64, s_hash_uint64, s_eq_uint64, NULL, NULL);

    /* Cache JNI method IDs */
    JNIEnv *env = NULL;
    (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
    if (env && java_callback) {
        jclass cls = (*env)->GetObjectClass(env, java_callback);
        h->on_publish_received_mid = (*env)->GetMethodID(env, cls, "onPublishReceived", "(Ljava/lang/String;[BIZ)V");
        h->on_connection_success_mid = (*env)->GetMethodID(env, cls, "onConnectionSuccess", "(Z)V");
        h->on_connection_failure_mid = (*env)->GetMethodID(env, cls, "onConnectionFailure", "(I)V");
        h->on_disconnection_mid = (*env)->GetMethodID(env, cls, "onDisconnection", "(I)V");
        h->on_ack_received_mid = (*env)->GetMethodID(env, cls, "onAckReceived", "(II)V");
        (*env)->DeleteLocalRef(env, cls);
    }

    return h;
}

void coremqtt_channel_handler_destroy(struct coremqtt_channel_handler *handler) {
    if (handler == NULL) return;

    aws_hash_table_clean_up(&handler->pending_acks);

    if (handler->client_id != NULL) {
        aws_mem_release(handler->allocator, handler->client_id);
    }

    if (handler->java_callback != NULL) {
        JNIEnv *env = NULL;
        (*handler->jvm)->AttachCurrentThread(handler->jvm, (void **)&env, NULL);
        if (env) {
            (*env)->DeleteGlobalRef(env, handler->java_callback);
        }
    }

    aws_mem_release(handler->allocator, handler);
}
