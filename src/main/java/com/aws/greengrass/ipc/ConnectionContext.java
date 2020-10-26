/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.common.FrameReader;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

@AllArgsConstructor
@ToString(of = {"serviceName", "remoteAddress", "clientId"})
@EqualsAndHashCode(of = {"serviceName", "clientId"})
public class ConnectionContext {
    private final Set<Runnable> disconnectHandlers = new HashSet<>();

    @Getter
    private final String serviceName;
    @Getter
    private final SocketAddress remoteAddress;
    @Getter
    private final String clientId = UUID.randomUUID().toString();

    private final IPCRouter router;

    public void onDisconnect(Runnable r) {
        disconnectHandlers.add(r);
    }

    public Future<FrameReader.Message> serverPush(int destination, FrameReader.Message msg) {
        return router.sendAndReceive(this, destination, msg);
    }

    void clientDisconnected() {
        disconnectHandlers.forEach(Runnable::run);
    }
}
