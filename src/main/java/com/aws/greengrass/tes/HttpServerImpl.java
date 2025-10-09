/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class HttpServerImpl implements Server {
    public static final String URL = "/2016-11-01/credentialprovider/";
    private static final int TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS = 1;
    private final HttpServer httpImpl;

    private final HttpHandler credentialRequestHandler;

    /**
     * Constructor.
     * 
     * @param port Http server port
     * @param credentialRequestHandler request handler for server requests
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler) throws IOException {
        httpImpl = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        this.credentialRequestHandler = credentialRequestHandler;
    }

    @Override
    public void start() {
        httpImpl.createContext(URL, credentialRequestHandler);
        // Use the default single thread (only one connection at a time will be processed, other connections will
        // backlog at TCP level).
        httpImpl.setExecutor(null);
        httpImpl.start();
    }

    @Override
    public void stop() {
        httpImpl.stop(TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS);
    }

    int getServerPort() {
        return httpImpl.getAddress().getPort();
    }
}
