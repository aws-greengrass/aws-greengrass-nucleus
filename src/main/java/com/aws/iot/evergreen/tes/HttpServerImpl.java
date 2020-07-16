/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.inject.Inject;

public class HttpServerImpl implements Server {
    public static final String URL = "/2016-11-01/credentialprovider/";
    private static final int TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS = 1;
    private final HttpServer httpImpl;

    @Inject
    private final HttpHandler credentialRequestHandler;

    /**
     * Constructor.
     * @param port Http server port
     * @param credentialRequestHandler request handler for server requests
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler) throws IOException {
        // Only start the server on default loopback (127.0.0.1)
        // TODO: Validate this works with mutiple loopback interfaces
        httpImpl = HttpServer.create(new InetSocketAddress(InetAddress.getByName(null), port), 0);
        this.credentialRequestHandler = credentialRequestHandler;
    }

    @Override
    public void start() {
        httpImpl.createContext(URL, credentialRequestHandler);
        // TODO: Evaluate if we need custom executor or shared executor with kernel
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
