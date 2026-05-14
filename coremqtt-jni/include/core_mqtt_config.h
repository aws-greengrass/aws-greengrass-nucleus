/*
 * coreMQTT compile-time configuration for Greengrass Nucleus PoC.
 * Transport is non-blocking: recv returns 0 when no data, send is immediate via channel.
 * All coreMQTT calls happen on the aws-c-io event loop thread (single-threaded).
 */
#ifndef CORE_MQTT_CONFIG_H
#define CORE_MQTT_CONFIG_H

#include <stdint.h>

/* Non-blocking recv. Data arrives via channel read callback, not polling. */
#define MQTT_RECV_POLLING_TIMEOUT_MS    ( 0U )

/* Non-blocking send. Channel slot send is immediate. */
#define MQTT_SEND_TIMEOUT_MS            ( 0U )

/* Wait 30s for PINGRESP before declaring keep-alive timeout. */
#define MQTT_PINGRESP_TIMEOUT_MS        ( 30000U )

/* Send PINGREQ after 30s of TX inactivity. */
#define PACKET_TX_TIMEOUT_MS            ( 30000U )

/* Send PINGREQ after 30s of RX inactivity. */
#define PACKET_RX_TIMEOUT_MS            ( 30000U )

/* CONNACK receive retries. */
#define MQTT_MAX_CONNACK_RECEIVE_RETRY_COUNT ( 5U )

/* Max vectors for subscribe/unsubscribe packets. */
#define MQTT_SUB_UNSUB_MAX_VECTORS      ( 4U )

/*
 * Threading: All coreMQTT calls happen on the single aws-c-io event loop thread.
 * No mutex needed. Assert correct thread in debug builds.
 */
#define MQTT_PRE_STATE_UPDATE_HOOK( pContext )   /* single-threaded, no-op */
#define MQTT_POST_STATE_UPDATE_HOOK( pContext )  /* single-threaded, no-op */

/* Logging - disabled for PoC build. coreMQTT uses double-parenthesis style:
 * LogError( ( "format %d", arg ) ) which requires custom printf-like macros. */
#define LogError( message )
#define LogWarn( message )
#define LogInfo( message )
#define LogDebug( message )
#define LogTrace( message )

#endif /* CORE_MQTT_CONFIG_H */
