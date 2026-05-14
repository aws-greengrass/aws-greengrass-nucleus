/*
 * JNI entry points for the coreMQTT channel handler adapter.
 * These are called from Java CoreMqttNative class.
 */
#include "coremqtt_channel_handler.h"

#include <aws/common/allocator.h>
#include <aws/io/channel_bootstrap.h>
#include <aws/io/socket.h>
#include <aws/io/tls_channel_handler.h>
#include <jni.h>
#include <string.h>

/* Helper: get C string from jstring (caller must free) */
static char *s_jstring_to_cstr(JNIEnv *env, jstring jstr, struct aws_allocator *alloc) {
    if (jstr == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    size_t len = strlen(utf);
    char *copy = aws_mem_calloc(alloc, 1, len + 1);
    memcpy(copy, utf, len + 1);
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return copy;
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    create
 * Signature: (ILjava/lang/Object;)J
 *
 * Creates the channel handler. Does NOT connect yet.
 */
JNIEXPORT jlong JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_create(
    JNIEnv *env,
    jclass cls,
    jint keep_alive_sec,
    jobject java_callback) {

    (void)cls;
    struct aws_allocator *alloc = aws_default_allocator();
    JavaVM *jvm = NULL;
    (*env)->GetJavaVM(env, &jvm);

    jobject callback_ref = (*env)->NewGlobalRef(env, java_callback);

    struct coremqtt_channel_handler *handler = coremqtt_channel_handler_new(
        alloc, jvm, callback_ref, (uint16_t)keep_alive_sec);

    return (jlong)(uintptr_t)handler;
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    destroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_destroy(
    JNIEnv *env,
    jclass cls,
    jlong handle) {

    (void)env;
    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;
    coremqtt_channel_handler_destroy(handler);
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    connect
 * Signature: (JLjava/lang/String;IJJLjava/lang/String;)V
 *
 * Initiates TCP+TLS connection via aws-c-io bootstrap, then sends MQTT CONNECT.
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_connect(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jstring jendpoint,
    jint port,
    jlong bootstrap_handle,
    jlong tls_ctx_handle,
    jstring jclient_id) {

    (void)cls;
    struct aws_allocator *alloc = aws_default_allocator();
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;

    const char *endpoint = (*env)->GetStringUTFChars(env, jendpoint, NULL);
    const char *client_id = (*env)->GetStringUTFChars(env, jclient_id, NULL);

    /* Store client ID in the MQTT context for CONNECT */
    /* TODO: store client_id in handler struct for use in channel_setup */

    struct aws_client_bootstrap *bootstrap = (struct aws_client_bootstrap *)(uintptr_t)bootstrap_handle;
    struct aws_tls_ctx *tls_ctx = (struct aws_tls_ctx *)(uintptr_t)tls_ctx_handle;

    /* Set up TLS connection options */
    struct aws_tls_connection_options tls_options;
    AWS_ZERO_STRUCT(tls_options);
    aws_tls_connection_options_init_from_ctx(&tls_options, tls_ctx);
    struct aws_byte_cursor host_cursor = aws_byte_cursor_from_c_str(endpoint);
    aws_tls_connection_options_set_server_name(&tls_options, alloc, &host_cursor);

    /* Socket options */
    struct aws_socket_options socket_options = {
        .type = AWS_SOCKET_STREAM,
        .domain = AWS_SOCKET_IPV4,
        .connect_timeout_ms = 10000,
    };

    /* Initiate connection through aws-c-io bootstrap */
    struct aws_socket_channel_bootstrap_options channel_options = {
        .bootstrap = bootstrap,
        .host_name = endpoint,
        .port = (uint32_t)port,
        .socket_options = &socket_options,
        .tls_options = &tls_options,
        .setup_callback = coremqtt_on_channel_setup,
        .shutdown_callback = coremqtt_on_channel_shutdown,
        .user_data = handler,
    };

    int result = aws_client_bootstrap_new_socket_channel(&channel_options);
    if (result != AWS_OP_SUCCESS) {
        JNIEnv *cb_env = env;
        if (handler->java_callback) {
            (*cb_env)->CallVoidMethod(cb_env, handler->java_callback,
                                     handler->on_connection_failure_mid, (jint)aws_last_error());
        }
    }

    (*env)->ReleaseStringUTFChars(env, jendpoint, endpoint);
    (*env)->ReleaseStringUTFChars(env, jclient_id, client_id);
    aws_tls_connection_options_clean_up(&tls_options);
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    disconnect
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_disconnect(
    JNIEnv *env,
    jclass cls,
    jlong handle) {

    (void)env;
    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;

    if (handler->is_connected && handler->slot && handler->slot->channel) {
        MQTTSuccessFailReasonCode_t reason = 0; /* Normal disconnection */
        MQTT_Disconnect(&handler->mqtt_ctx, NULL, &reason);
        aws_channel_shutdown(handler->slot->channel, 0);
    }
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    publish
 * Signature: (JLjava/lang/String;[BIZLjava/lang/Object;)V
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_publish(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jstring jtopic,
    jbyteArray jpayload,
    jint qos,
    jboolean retain,
    jobject jfuture) {

    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;

    const char *topic = (*env)->GetStringUTFChars(env, jtopic, NULL);
    jsize payload_len = jpayload ? (*env)->GetArrayLength(env, jpayload) : 0;
    jbyte *payload = payload_len > 0 ? (*env)->GetByteArrayElements(env, jpayload, NULL) : NULL;

    jobject future_ref = (*env)->NewGlobalRef(env, jfuture);

    coremqtt_publish(handler, topic, (const uint8_t *)payload, (size_t)payload_len,
                     (MQTTQoS_t)qos, (bool)retain, future_ref);

    if (payload) (*env)->ReleaseByteArrayElements(env, jpayload, payload, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jtopic, topic);
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    subscribe
 * Signature: (JLjava/lang/String;ILjava/lang/Object;)V
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_subscribe(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jstring jtopic,
    jint qos,
    jobject jfuture) {

    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;

    const char *topic = (*env)->GetStringUTFChars(env, jtopic, NULL);
    jobject future_ref = (*env)->NewGlobalRef(env, jfuture);

    coremqtt_subscribe(handler, topic, (MQTTQoS_t)qos, future_ref);

    (*env)->ReleaseStringUTFChars(env, jtopic, topic);
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    unsubscribe
 * Signature: (JLjava/lang/String;Ljava/lang/Object;)V
 */
JNIEXPORT void JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_unsubscribe(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jstring jtopic,
    jobject jfuture) {

    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;

    const char *topic = (*env)->GetStringUTFChars(env, jtopic, NULL);
    jobject future_ref = (*env)->NewGlobalRef(env, jfuture);

    coremqtt_unsubscribe(handler, topic, future_ref);

    (*env)->ReleaseStringUTFChars(env, jtopic, topic);
}

/*
 * Class:     com_aws_greengrass_mqttclient_CoreMqttNative
 * Method:    isConnected
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_com_aws_greengrass_mqttclient_CoreMqttNative_isConnected(
    JNIEnv *env,
    jclass cls,
    jlong handle) {

    (void)env;
    (void)cls;
    struct coremqtt_channel_handler *handler = (struct coremqtt_channel_handler *)(uintptr_t)handle;
    return (jboolean)handler->is_connected;
}
