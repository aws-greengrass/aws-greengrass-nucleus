cmake_minimum_required(VERSION 3.12)
project(coremqtt-jni C)

# Find JNI headers
set(JAVA_HOME $ENV{JAVA_HOME})
if(NOT JAVA_HOME)
    execute_process(COMMAND /usr/libexec/java_home OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
endif()
if(NOT JAVA_HOME)
    # Fallback for Amazon Corretto in Docker
    file(GLOB JAVA_HOME_CANDIDATES "/usr/lib/jvm/java-*" "/opt/homebrew/Cellar/openjdk*/*/libexec/openjdk.jdk/Contents/Home")
    list(GET JAVA_HOME_CANDIDATES 0 JAVA_HOME)
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include")
if(APPLE)
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/darwin")
elseif(UNIX)
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/linux")
endif()

# coreMQTT source directory (submodule or cloned)
set(COREMQTT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/coreMQTT")

# CRT install prefix (passed via -DCMAKE_PREFIX_PATH)
if(CMAKE_PREFIX_PATH)
    set(CRT_PREFIX "${CMAKE_PREFIX_PATH}")
else()
    set(CRT_PREFIX "/tmp/aws-crt-install")
endif()

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
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${COREMQTT_DIR}/source/include
    ${COREMQTT_DIR}/source/include/private
    ${COREMQTT_DIR}/source/interface
    ${JNI_INCLUDE_DIRS}
    ${CRT_PREFIX}/include
)

# Link CRT static libraries
find_library(AWS_C_IO_LIB aws-c-io PATHS ${CRT_PREFIX}/lib ${CRT_PREFIX}/lib64 NO_DEFAULT_PATH)
find_library(AWS_C_COMMON_LIB aws-c-common PATHS ${CRT_PREFIX}/lib ${CRT_PREFIX}/lib64 NO_DEFAULT_PATH)
find_library(AWS_C_CAL_LIB aws-c-cal PATHS ${CRT_PREFIX}/lib ${CRT_PREFIX}/lib64 NO_DEFAULT_PATH)
find_library(S2N_LIB s2n PATHS ${CRT_PREFIX}/lib ${CRT_PREFIX}/lib64 NO_DEFAULT_PATH)
target_link_libraries(coremqtt_jni PRIVATE ${AWS_C_IO_LIB} ${AWS_C_CAL_LIB} ${S2N_LIB} ${AWS_C_COMMON_LIB})

# Platform-specific link dependencies
if(APPLE)
    target_link_libraries(coremqtt_jni PRIVATE "-framework Security" "-framework CoreFoundation" "-framework Network")
elseif(UNIX)
    target_link_libraries(coremqtt_jni PRIVATE crypto pthread dl)
    target_link_options(coremqtt_jni PRIVATE -Wl,-Bsymbolic)
endif()

set_target_properties(coremqtt_jni PROPERTIES
    C_STANDARD 99
    POSITION_INDEPENDENT_CODE ON
)
