package com.aws.iot.evergreen.ipc.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.net.SocketAddress;
import java.util.UUID;

@AllArgsConstructor
@Data
@ToString
@EqualsAndHashCode
public class ConnectionContext {
    private String serviceName;
    private SocketAddress remoteAddress;
    private final String clientId = UUID.randomUUID().toString();
}
