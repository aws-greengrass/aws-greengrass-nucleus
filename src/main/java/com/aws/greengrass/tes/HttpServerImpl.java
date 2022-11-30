/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

public class HttpServerImpl implements Server {
    static final String URL = "/2016-11-01/credentialprovider/";
    private static final int TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS = 1;
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String ADDR_IPV4 = "127.0.0.1";
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String ADDR_IPV6 = "::1";

    /**
     * HTTP server port, provided by configuration.
     */
    private final int configuredPort;

    /**
     * The actual HTTP server port, since the
     * configured port may be system-assigned (0).
     */
    private final int resolvedPort;

    /**
     * Reference to servers, we attempt to have one
     * for IPV4 and IPV6, if those stacks are available.
     */
    private final List<HttpServer> servers;

    @Inject
    private final HttpHandler credentialRequestHandler;

    @Inject
    private final ExecutorService executorService;

    /**
     * Constructor.
     * @param port HTTP server port
     * @param credentialRequestHandler request handler for server requests
     * @param executorService executor service instance
     * @throws IOException When server creation fails
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler, ExecutorService executorService) throws IOException {
        this.credentialRequestHandler = credentialRequestHandler;
        this.executorService = executorService;
        this.configuredPort = port;
        this.servers = createServers(configuredPort);
        this.resolvedPort = this.servers.get(0).getAddress().getPort();
    }

    /**
     * Create ipv4 and ipv6 servers, both using the specified port.
     *
     * @param port chosen port for all created servers
     * @return list of http servers
     * @throws IOException if an I/O error occurs during server creation
     * @throws BindException if the chosen port is unavailable
     */
    private List<HttpServer> createServers(int port) throws IOException {
        HttpServer ipv4 = null;
        try {
            InetSocketAddress addr = new InetSocketAddress(ADDR_IPV4, port);
            ipv4 = HttpServer.create(addr, 0);
        } catch (BindException e) {
            if (canBindToAddress(ADDR_IPV4)) {
                throw e;
            }
            // IPv4 is likely unavailable, proceed without server.
        }

        HttpServer ipv6 = null;
        try {
            InetSocketAddress addr = new InetSocketAddress(ADDR_IPV6,
                    // use the same port from ipv4 server
                    ipv4 == null ? port : ipv4.getAddress().getPort());
            ipv6 = HttpServer.create(addr, 0);
        } catch (BindException e) {
            if (canBindToAddress(ADDR_IPV6)) {
                if (ipv4 != null) { // cleanup
                    stop(ipv4);
                }
                throw e;
            }
            if (ipv4 == null) {
                // unable to create either server. likely indicative of a larger issue,
                // such as no ports being available.
                throw e;
            }
            // IPv6 is likely unavailable, proceed without server.
        }

        return Stream.of(ipv4, ipv6).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private boolean canBindToAddress(String hostname) {
        try (Socket s = new Socket()) {
            s.bind(new InetSocketAddress(hostname, 0));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void start() {
        servers.forEach(this::start);
    }

    private void start(HttpServer server) {
        server.createContext(URL, credentialRequestHandler);
        server.setExecutor(executorService);
        server.start();
    }

    @Override
    public void stop() {
        servers.forEach(this::stop);
    }

    private void stop(HttpServer server) {
        server.stop(TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS);
    }

    int getServerPort() {
        return resolvedPort;
    }
}
