/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
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

    private static final Logger logger = LogManager.getLogger(HttpServerImpl.class);
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
        HttpServer serverIPv4 = null;
        InetSocketAddress addrIPv4 = new InetSocketAddress(ADDR_IPV4, port);
        try {
            serverIPv4 = HttpServer.create(addrIPv4, 0);
        } catch (BindException e) {
            if (canBindToAddress(ADDR_IPV4)) {
                throw e;
            }
            logger.atDebug().cause(e).kv("address", addrIPv4)
                    .log("Unable to bind HTTP server. "
                            + "Proceeding anyway as IPv4 is likely unavailable on the host system");
        }

        HttpServer serverIPv6 = null;
        InetSocketAddress addrIPv6 =
                new InetSocketAddress(ADDR_IPV6,
                // use the same port from IPv4 server
                serverIPv4 == null ? port : serverIPv4.getAddress().getPort());
        try {
            serverIPv6 = HttpServer.create(addrIPv6, 0);
        } catch (BindException e) {
            if (canBindToAddress(ADDR_IPV6)) {
                if (serverIPv4 != null) { // cleanup
                    stop(serverIPv4);
                }
                throw e;
            }
            if (serverIPv4 == null) {
                // unable to create either server. likely indicative of a larger issue,
                // such as no ports being available.
                throw e;
            }
            logger.atDebug().cause(e).kv("address", addrIPv4)
                    .log("Unable to bind HTTP server. "
                            + "Proceeding anyway as IPv6 is likely unavailable on the host system");
        }

        return Stream.of(serverIPv4, serverIPv6).filter(Objects::nonNull).collect(Collectors.toList());
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

    List<InetSocketAddress> getServerAddresses() {
        return servers.stream()
                .map(HttpServer::getAddress)
                .collect(Collectors.toList());
    }

    int getServerPort() {
        return resolvedPort;
    }
}
