/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

public class HttpServerImpl implements Server {
    public static final String URL = "/2016-11-01/credentialprovider/";
    private static final int STOP_TIMEOUT_SECONDS = 1;
    private static final String ANY_INTERFACE = "::";
    private HttpServer server;
    private final int configuredPort;
    private final HttpHandler credentialRequestHandler;
    private final ExecutorService executorService;

    /**
     * Constructor.
     * @param port Http server port
     * @param credentialRequestHandler request handler for server requests
     * @param executorService executor service instance
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler, ExecutorService executorService)
            throws IOException {
        this.configuredPort = port;
        this.credentialRequestHandler = credentialRequestHandler;
        this.executorService = executorService;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(ANY_INTERFACE, configuredPort), 0);
        server.createContext(URL, credentialRequestHandler);
        server.setExecutor(executorService);
        server.start();
    }

    @Override
    public void stop() {
        server.stop(STOP_TIMEOUT_SECONDS);
    }

    int getServerPort() {
        return server.getAddress().getPort();
    }
}
