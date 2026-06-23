# Stage 1: Build native library and nucleus JAR
FROM --platform=linux/arm64 amazoncorretto:8 AS builder

RUN yum install -y gcc gcc-c++ cmake3 make git openssl-devel curl tar gzip && \
    ln -sf /usr/bin/cmake3 /usr/bin/cmake && \
    curl -sL https://archive.apache.org/dist/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-3.8.8/bin/mvn /usr/bin/mvn

WORKDIR /build

# Copy nucleus source
COPY . nucleus/
# Clone coreMQTT into the expected location (Docker COPY doesn't preserve git submodules)
RUN rm -rf nucleus/coremqtt-jni/coreMQTT && \
    git clone --depth 1 https://github.com/FreeRTOS/coreMQTT.git nucleus/coremqtt-jni/coreMQTT

# Clone aws-crt-java and init submodules
RUN git clone --depth 1 https://github.com/awslabs/aws-crt-java.git && \
    cd aws-crt-java && git submodule update --init crt/aws-c-common crt/aws-c-cal crt/aws-c-sdkutils crt/aws-c-io crt/s2n

# Build CRT deps: aws-c-common
RUN cd aws-crt-java/crt/aws-c-common && mkdir -p build && cd build && \
    cmake .. -DCMAKE_INSTALL_PREFIX=/opt/crt -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-fPIC" && \
    make -j2 install

# Build aws-c-sdkutils
RUN cd aws-crt-java/crt/aws-c-sdkutils && mkdir -p build && cd build && \
    cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_INSTALL_PREFIX=/opt/crt -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-fPIC" && \
    make -j2 install

# Build aws-c-cal
RUN cd aws-crt-java/crt/aws-c-cal && mkdir -p build && cd build && \
    cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_INSTALL_PREFIX=/opt/crt -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-fPIC" && \
    make -j2 install

# Build s2n-tls
RUN cd aws-crt-java/crt/s2n && mkdir -p build && cd build && \
    cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_INSTALL_PREFIX=/opt/crt -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-fPIC" && \
    make -j2 install

# Build aws-c-io
RUN cd aws-crt-java/crt/aws-c-io && mkdir -p build && cd build && \
    cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_INSTALL_PREFIX=/opt/crt -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-fPIC" && \
    make -j2 install

# Build coremqtt-jni
RUN cd nucleus/coremqtt-jni && rm -rf build && mkdir build && cd build && \
    cmake .. -DCMAKE_PREFIX_PATH=/opt/crt -DCMAKE_BUILD_TYPE=Release && \
    make -j2

# Build nucleus JAR
RUN cd nucleus && \
    mvn package -DskipTests -Dcheckstyle.skip -Dpmd.skip -Dspotbugs.skip -Dlicense.skip

# Stage 2: Runtime
FROM --platform=linux/arm64 amazoncorretto:8

RUN yum install -y shadow-utils openssl openssl-devel python3 python3-pip && \
    pip3 install 'awsiotsdk==1.19.0'

WORKDIR /greengrass

COPY --from=builder /build/nucleus/target/Greengrass.jar .
COPY --from=builder /build/nucleus/coremqtt-jni/build/libcoremqtt_jni.so /usr/lib64/
COPY --from=builder /build/nucleus/conf/recipe.yaml /greengrass/nucleus-recipe.yaml

# Set up the proper nucleus directory structure
# The nucleus expects: <unpack_dir>/lib/Greengrass.jar and <unpack_dir>/bin/ to exist
RUN mkdir -p /greengrass/alts/current/distro/lib && \
    mkdir -p /greengrass/alts/current/distro/bin && \
    mkdir -p /greengrass/alts/current/distro/conf && \
    cp /greengrass/Greengrass.jar /greengrass/alts/current/distro/lib/Greengrass.jar && \
    cp /greengrass/nucleus-recipe.yaml /greengrass/alts/current/distro/conf/recipe.yaml && \
    sed -i 's/ComponentVersion: .*/ComponentVersion: "2.18.0-SNAPSHOT"/' /greengrass/alts/current/distro/conf/recipe.yaml && \
    echo '#!/bin/sh' > /greengrass/alts/current/distro/bin/loader && \
    echo 'exec java -Djava.library.path=/usr/lib64 -jar /greengrass/alts/current/distro/lib/Greengrass.jar "$@"' >> /greengrass/alts/current/distro/bin/loader && \
    chmod +x /greengrass/alts/current/distro/bin/loader && \
    echo '-Djava.library.path=/usr/lib64' > /greengrass/alts/current/launch.params

RUN ldconfig

# Run from the lib directory so the JAR's code source location resolves correctly
ENTRYPOINT ["java", "-Djava.library.path=/usr/lib64", "-jar", \
            "/greengrass/alts/current/distro/lib/Greengrass.jar", \
            "--init-config", "/greengrass/config/config.yaml", \
            "--component-default-user", "root"]
