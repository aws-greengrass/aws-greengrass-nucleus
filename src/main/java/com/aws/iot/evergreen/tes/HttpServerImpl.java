/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HttpServerImpl implements Server {
    public static final String URL = "/2016-11-01/credentialprovider/";
    private static final int timeToWaitBeforeShutdownInSeconds = 1;
    private final HttpServer httpImpl;

    @Inject
    private HttpHandler credentialRequestHandler;

    /**
     * Constructor.
     * @param port Http server port
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port) throws IOException {
        // TODO: validate port
        httpImpl = HttpServer.create(new InetSocketAddress(port), 0);
    }

    /**
     * Constructor for Unit testing.
     * @param port Http server port
     * @param credentialRequestHandler request handler for server requests
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler) throws IOException {
        httpImpl = HttpServer.create(new InetSocketAddress(port), 0);
        this.credentialRequestHandler = credentialRequestHandler;
    }

    @Override
    public void start() {
        httpImpl.createContext(URL, credentialRequestHandler);
        httpImpl.setExecutor(null);
        httpImpl.start();
    }

    @Override
    public void stop() {
        httpImpl.stop(timeToWaitBeforeShutdownInSeconds);
    }
}
