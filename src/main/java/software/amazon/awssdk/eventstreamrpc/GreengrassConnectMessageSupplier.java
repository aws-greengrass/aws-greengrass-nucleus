/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

import com.google.gson.Gson;
import software.amazon.awssdk.crt.eventstream.Header;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import static software.amazon.awssdk.eventstreamrpc.EventStreamRPCServiceModel.VERSION_HEADER;

public class GreengrassConnectMessageSupplier {

    private static final String IPC_PROTOCOL_VERSION = "0.1.0";
    
    public static Supplier<MessageAmendInfo> connectMessageSupplier(String authToken) {
        return () -> {
            final List<Header> headers = new LinkedList<>();
            headers.add(Header.createHeader(VERSION_HEADER, IPC_PROTOCOL_VERSION));
            GreengrassEventStreamConnectMessage connectMessage = new GreengrassEventStreamConnectMessage();
            connectMessage.setAuthToken(authToken);
            String payload = new Gson().toJson(connectMessage);
            return new MessageAmendInfo(headers, payload.getBytes(StandardCharsets.UTF_8));
        };
    }
}
