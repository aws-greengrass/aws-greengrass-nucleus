# coreMQTT PoC — Replace aws-c-mqtt in Greengrass Nucleus

## Quick Start

### Prerequisites
- Podman or Docker
- AWS CLI configured with IoT permissions
- An IoT thing with certificate, private key, and policy allowing `iot:Connect`, `iot:Publish`, `iot:Subscribe`, `iot:Receive` on `*`

### 1. Clone and init submodules
```bash
git clone --branch feature/coremqtt-poc https://github.com/AniruddhaKanhere/aws-greengrass-nucleus.git
cd aws-greengrass-nucleus
git submodule update --init --recursive
```

### 2. Set up device config
```bash
cp coremqtt-jni/config.yaml.example config.yaml
# Edit config.yaml: set thingName, region, iotDataEndpoint
```

### 3. Place certs
```bash
mkdir certs/
# Copy your certificate.pem.crt, private.pem.key, AmazonRootCA1.pem into certs/
```

### 4. Build
```bash
cd coremqtt-jni
podman build -t gg-coremqtt .
```

### 5. Run
```bash
podman run -d --name gg-coremqtt \
  -v $(pwd)/../config.yaml:/greengrass/config/config.yaml:ro \
  -v $(pwd)/../certs:/greengrass/certs:ro \
  gg-coremqtt
```

### 6. Verify
```bash
# Check device registered
aws greengrassv2 get-core-device --core-device-thing-name <YOUR_THING> --region <YOUR_REGION>

# Deploy the demo publisher component
aws greengrassv2 create-component-version --inline-recipe fileb://publisher-component-recipe.json --region <YOUR_REGION>
aws greengrassv2 create-deployment \
  --target-arn "arn:aws:iot:<REGION>:<ACCOUNT>:thing/<THING>" \
  --components '{"com.example.CoreMQTTPublisher":{"componentVersion":"1.1.0"}}' \
  --region <YOUR_REGION>

# Watch messages arrive on IoT Core MQTT test client: topic gg/coremqtt/hello
```

> **Note:** The container needs `python3` and `awsiotsdk==1.19.0` pre-installed for the publisher component. The Dockerfile includes these.

---

## Implementation Guide

### Result
Greengrass nucleus running with coreMQTT (replacing aws-c-mqtt) registers as a **HEALTHY** core device on AWS IoT Core. All MQTT operations (connect, subscribe, publish) work through the aws-c-io channel pipeline with coreMQTT handling the MQTT5 protocol.

---

## Architecture

```
Java (MqttClient → CoreMqttJniClient)
    │ JNI
C (coremqtt_jni.c)
    │ creates
C (coremqtt_channel_handler.c) ← implements aws_channel_handler vtable
    │ plugs into
aws-c-io channel pipeline: [Socket] → [TLS/s2n] → [coreMQTT handler]
```

**What was replaced:** Only the MQTT protocol handler (aws-c-mqtt). TLS (s2n), sockets, event loops, DNS resolution — all remain from aws-c-io.

---

## Key Design Decisions

### 1. coreMQTT as a channel handler
coreMQTT is wrapped in an `aws_channel_handler` that sits at the end of the aws-c-io channel pipeline (same position aws-c-mqtt occupied). The handler's `TransportSend` writes to the channel (→ TLS → socket), and `TransportRecv` reads from a ring buffer filled by the channel's read callback.

### 2. Non-blocking CONNECT
`MQTT_Connect()` is synchronous (blocks waiting for CONNACK), but the channel setup callback runs on the event loop thread which must remain free to receive data. Solution: serialize the CONNECT packet manually using `MQTT_SerializeConnect()`, send it via the channel, and handle CONNACK arrival in `s_process_read_message` by parsing the packet type byte.

### 3. Own CRT infrastructure
The native library creates its own `aws_event_loop_group`, `aws_host_resolver`, and `aws_client_bootstrap` (separate from the CRT Java library's instances). This avoids symbol conflicts between our statically-linked aws-c-io and the CRT Java's copy. Linked with `-Bsymbolic` to prevent symbol interposition.

### 4. JNI_OnLoad for initialization
`aws_io_library_init()` must be called before any aws-c-io function. Done in `JNI_OnLoad` with `__attribute__((visibility("default")))` to ensure the symbol is exported despite `-Bsymbolic`.

---

## Files Created/Modified

### Native (C) — `coremqtt-jni/`
| File | Purpose |
|------|---------|
| `CMakeLists.txt` | Build config. Links coreMQTT sources + aws-c-io/s2n/aws-c-common statically. Uses `-Bsymbolic`. |
| `include/core_mqtt_config.h` | coreMQTT compile-time config: non-blocking transport, logging macros, `MQTT_MAX_CONNACK_RECEIVE_RETRY_COUNT=0` |
| `src/coremqtt_channel_handler.h` | Handler struct: MQTTContext, ring buffer, pending ACKs hash table, JNI refs, connection state |
| `src/coremqtt_channel_handler.c` | Channel handler implementation (~900 lines) |
| `src/coremqtt_jni.c` | JNI entry points (~340 lines) |
| `coreMQTT` | Git submodule → github.com/FreeRTOS/coreMQTT |

### Java — `src/main/java/com/aws/greengrass/mqttclient/`
| File | Purpose |
|------|---------|
| `CoreMqttNative.java` | JNI method declarations |
| `CoreMqttJniClient.java` | `IndividualMqttClient` implementation |
| `MqttClient.java` | Modified: routes `"coremqtt"` version to `CoreMqttJniClient` |

### Build
| File | Purpose |
|------|---------|
| `pom.xml` | Added `coremqtt-jni/**` to license check exclusion |
| `.gitmodules` | coreMQTT submodule reference |

---

## Critical Implementation Details

### Transport Interface
- **`TransportRecv`**: Reads from a 256KB ring buffer. Returns 0 when empty (non-blocking). Buffer is filled by `s_process_read_message` channel callback.
- **`TransportSend`**: Acquires `aws_io_message` from channel pool, copies bytes, calls `aws_channel_slot_send_message(WRITE)`. Always succeeds immediately.
- **`TransportWritev`**: Combines all vectors into a single `aws_io_message`. Critical — without this, the CONNECT packet was split across multiple TLS records and IoT Core didn't respond.

### Connection Flow
1. Java calls `CoreMqttNative.connect(handle, endpoint, port, bootstrap, certPath, keyPath, caPath, clientId)`
2. JNI creates `aws_tls_ctx` from cert/key/CA paths
3. Calls `aws_client_bootstrap_new_socket_channel()` with TLS options
4. Channel setup callback fires (TCP+TLS complete):
   - Inserts handler at end of channel pipeline
   - Serializes MQTT CONNECT packet via `MQTT_SerializeConnect()`
   - Sends via `TransportSend`
   - Sets `waiting_for_connack = true`
5. CONNACK arrives in `s_process_read_message`:
   - Checks first byte is `0x20` (CONNACK type)
   - Consumes packet from ring buffer
   - Sets `connectStatus = MQTTConnected`, `is_connected = true`
   - Notifies Java via JNI upcall to `onConnectionSuccess(sessionPresent)`
6. Subsequent data → `MQTT_ProcessLoop()` handles SUBACK, PUBACK, incoming PUBLISH

### Subscribe Flow
1. Java calls `CoreMqttNative.subscribe(handle, topic, qos, future)`
2. JNI creates global ref to future, schedules event loop task
3. Task runs on event loop: `MQTT_GetPacketId()` → store in `pending_acks` hash table → `MQTT_Subscribe()`
4. SUBACK arrives → `s_process_read_message` → `MQTT_ProcessLoop` → event callback
5. Event callback: looks up packet ID in `pending_acks` → calls `CompletableFuture.complete(Integer)` via JNI

### Key Bugs Fixed
| Bug | Symptom | Fix |
|-----|---------|-----|
| `MQTT_Connect` blocks event loop | CONNACK never arrives (deadlock) | Use `MQTT_SerializeConnect` + handle CONNACK manually |
| Split CONNECT across TLS records | IoT Core doesn't respond | Implement `TransportWritev` to send as single message |
| Stale CONNACK in networkBuffer | `MQTTBadResponse` on reconnect | Reset `mqtt_ctx.index=0` and `memset` buffer in channel_setup |
| ProcessLoop on failed connections | Crashes parsing stale data | Check `is_connected` before calling ProcessLoop |
| `connect()` reconnects when already connected | DUPLICATE_CLIENT_ID loop | Return `CompletableFuture.completedFuture(null)` if `connected()` |
| JNI_OnLoad not exported | `s_io_library_initialized` assertion | Add `__attribute__((visibility("default")))` |
| Symbol interposition with CRT | Init sets wrong copy of global | Link with `-Bsymbolic` |

---

## Build & Run

### Prerequisites
- aws-crt-java repo with submodules (for aws-c-io, aws-c-common, aws-c-cal, s2n headers)
- CRT libraries built and installed to a prefix (e.g., `/opt/crt`)
- JDK 8+ with JNI headers
- IoT Core thing with cert/key/CA and permissive policy

### Build Native Library
```bash
cd coremqtt-jni && mkdir build && cd build
cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_BUILD_TYPE=Release
make -j$(nproc)
# Produces libcoremqtt_jni.so
```

### Build Nucleus JAR
```bash
mvn package -DskipTests -Dcheckstyle.skip -Dpmd.skip -Dspotbugs.skip -Dlicense.skip
```

### Run
```bash
java -Djava.library.path=/path/to/libcoremqtt_jni.so \
     -jar target/Greengrass.jar \
     --init-config config.yaml \
     --component-default-user root
```

### Config (mqtt.version: "coremqtt")
```yaml
system:
  certificateFilePath: "/path/to/certificate.pem.crt"
  privateKeyPath: "/path/to/private.pem.key"
  rootCaPath: "/path/to/AmazonRootCA1.pem"
  rootpath: "/greengrass/v2"
  thingName: "MyThingName"
services:
  aws.greengrass.Nucleus:
    configuration:
      awsRegion: "us-west-2"
      iotDataEndpoint: "xxxxx-ats.iot.us-west-2.amazonaws.com"
      mqtt:
        version: "coremqtt"
```

---

## What This Proves
1. coreMQTT can replace aws-c-mqtt in the aws-c-io channel pipeline
2. The Greengrass nucleus works unmodified (spooler, subscription routing, lifecycle callbacks all function)
3. MQTT5 CONNECT, SUBSCRIBE, PUBLISH all work against AWS IoT Core
4. Device registers as HEALTHY Greengrass core device
