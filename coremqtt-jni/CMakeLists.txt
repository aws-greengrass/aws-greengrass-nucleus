cmake_minimum_required(VERSION 3.12)
project(coremqtt-jni C)

# Find JNI - use JAVA_HOME hint
set(JAVA_HOME $ENV{JAVA_HOME})
if(NOT JAVA_HOME)
    execute_process(COMMAND /usr/libexec/java_home OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
endif()
if(NOT JAVA_HOME)
    set(JAVA_HOME "/opt/homebrew/Cellar/openjdk@17/17.0.16/libexec/openjdk.jdk/Contents/Home")
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include")
if(APPLE)
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/darwin")
elseif(UNIX)
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/linux")
endif()

# Paths to dependencies (adjust if needed)
set(COREMQTT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/coreMQTT")
set(AWS_CRT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../aws-crt-java")

# coreMQTT sources
set(COREMQTT_SOURCES
    ${COREMQTT_DIR}/source/core_mqtt.c
    ${COREMQTT_DIR}/source/core_mqtt_serializer.c
    ${COREMQTT_DIR}/source/core_mqtt_state.c
    ${COREMQTT_DIR}/source/core_mqtt_serializer_private.c
    ${COREMQTT_DIR}/source/core_mqtt_prop_serializer.c
    ${COREMQTT_DIR}/source/core_mqtt_prop_deserializer.c
)

# Our adapter sources
set(ADAPTER_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/src/coremqtt_channel_handler.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/coremqtt_jni.c
)

add_library(coremqtt_jni SHARED ${COREMQTT_SOURCES} ${ADAPTER_SOURCES})

target_include_directories(coremqtt_jni PRIVATE
    # Our config header (must come first so coreMQTT picks it up)
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    # coreMQTT headers
    ${COREMQTT_DIR}/source/include
    ${COREMQTT_DIR}/source/include/private
    ${COREMQTT_DIR}/source/interface
    # JNI headers
    ${JNI_INCLUDE_DIRS}
    # Installed CRT headers
    /tmp/aws-crt-install/include
)

# Link against installed CRT libraries
target_link_directories(coremqtt_jni PRIVATE /tmp/aws-crt-install/lib)
target_link_libraries(coremqtt_jni PRIVATE aws-c-io aws-c-common aws-c-cal)

# On macOS, link frameworks
if(APPLE)
    target_link_libraries(coremqtt_jni PRIVATE "-framework Security" "-framework CoreFoundation" "-framework Network")
endif()

set_target_properties(coremqtt_jni PROPERTIES
    C_STANDARD 99
    POSITION_INDEPENDENT_CODE ON
)
