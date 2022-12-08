/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.RetryUtils;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.spi.HttpServerProvider;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.SocketFactory;

public class HttpServerImpl implements Server {

    private static final Logger logger = LogManager.getLogger(HttpServerImpl.class);
    static final String URL = "/2016-11-01/credentialprovider/";
    private static final int PORT_UNKNOWN = -1;
    private static final int TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS = 1;
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String ADDR_IPV4 = "127.0.0.1";
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String ADDR_IPV6 = "::1";

    private static final RetryUtils.RetryConfig SERVER_CREATION_RETRY_CONFIG = RetryUtils.RetryConfig.builder()
            .maxAttempt(Integer.MAX_VALUE)
            .initialRetryInterval(Duration.ofSeconds(1L))
            .maxRetryInterval(Duration.ofMinutes(1L))
            .retryableExceptions(Collections.singletonList(SocketException.class))
            .build();

    /**
     * HTTP server port, provided by configuration.
     *
     * <p>The actual HTTP server port may be different from this,
     * if configured port is system-assigned (0).
     */
    private final int configuredPort;

    private HttpServer serverIPv4;
    private HttpServer serverIPv6;

    private final HttpHandler credentialRequestHandler;
    private final ExecutorService executorService;
    private final SocketFactory socketFactory;
    private final HttpServerProvider httpServerProvider;

    /**
     * Constructor.
     * @param port HTTP server port
     * @param credentialRequestHandler request handler for server requests
     * @param executorService executor service instance
     */
    HttpServerImpl(int port, HttpHandler credentialRequestHandler, ExecutorService executorService) {
        this(port, credentialRequestHandler, executorService,
                HttpServerProvider.provider(), SocketFactory.getDefault());
    }

    HttpServerImpl(int port, HttpHandler credentialRequestHandler, ExecutorService executorService,
                   HttpServerProvider httpServerProvider, SocketFactory socketFactory) {
        this.credentialRequestHandler = credentialRequestHandler;
        this.executorService = executorService;
        this.configuredPort = port;
        this.httpServerProvider = httpServerProvider;
        this.socketFactory = socketFactory;
    }

    @Override
    public void start() throws IOException, InterruptedException {
        createServers(configuredPort);
        getServers().forEach(this::start);
    }

    private void start(HttpServer server) {
        server.createContext(URL, credentialRequestHandler);
        server.setExecutor(executorService);
        server.start();
    }

    @Override
    public void stop() {
        getServers().forEach(this::stop);
    }

    private void stop(HttpServer server) {
        server.stop(TIME_TO_WAIT_BEFORE_SHUTDOWN_IN_SECONDS);
    }

    List<InetSocketAddress> getServerAddresses() {
        return getServers().stream()
                .map(HttpServer::getAddress)
                .collect(Collectors.toList());
    }

    int getServerPort() {
        return getServers().stream()
                .findFirst()
                .map(HttpServer::getAddress)
                .map(InetSocketAddress::getPort)
                .orElse(PORT_UNKNOWN);
    }

    private List<HttpServer> getServers() {
        return Stream.of(serverIPv4, serverIPv6)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void createServers(int port) throws IOException, InterruptedException {
        if (!getServers().isEmpty()) {
            logger.atInfo().kv("port", port).log("Skipping server creation, servers already running");
            return;
        }
        // if port is system-picked, retry as we expect
        // this to eventually succeed.
        // otherwise, port is customer-configured,
        // ensure we raise bind errors immediately.
        if (port == 0) {
            createServersWithRetry(port);
        } else {
            doCreateServers(port);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private void createServersWithRetry(int port) throws IOException, InterruptedException {
        try {
            RetryUtils.runWithRetry(SERVER_CREATION_RETRY_CONFIG,
                    () -> {
                        doCreateServers(port);
                        return null;
                    },
                    "create-http-servers",
                    logger);
        } catch (InterruptedException | IOException e) { // pass-through from RetryUtils
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Unexpected exception during HTTP server creation", e);
        }
    }

    /**
     * Create ipv4 and ipv6 servers, both using the specified port.
     *
     * @param port chosen port for all created servers
     * @throws IOException if an I/O error occurs during server creation
     * @throws BindException if the chosen port is unavailable
     */
    private void doCreateServers(int port) throws IOException {
        InetSocketAddress addrIPv4 = new InetSocketAddress(ADDR_IPV4, port);
        try {
            serverIPv4 = httpServerProvider.createHttpServer(addrIPv4, 0);
        } catch (SocketException e) {
            if (canBindToAddress(ADDR_IPV4)) {
                throw e;
            }
            logger.atInfo().cause(e).kv("address", addrIPv4)
                    .log("Unable to bind HTTP server. "
                            + "Proceeding anyway as IPv4 is likely unavailable on the host system");
        }

        InetSocketAddress addrIPv6 =
                new InetSocketAddress(ADDR_IPV6,
                        // use the same port from IPv4 server
                        serverIPv4 == null ? port : serverIPv4.getAddress().getPort());
        try {
            serverIPv6 = httpServerProvider.createHttpServer(addrIPv6, 0);
        } catch (SocketException e) {
            if (canBindToAddress(ADDR_IPV6)) {
                stop();
                throw e;
            }
            if (serverIPv4 == null) {
                // unable to create either server. likely indicative of a larger issue,
                // such as no ports being available.
                throw e;
            }
            logger.atInfo().cause(e).kv("address", addrIPv6)
                    .log("Unable to bind HTTP server. "
                            + "Proceeding anyway as IPv6 is likely unavailable on the host system");
        }
    }

    private boolean canBindToAddress(String hostname) {
        try (Socket s = socketFactory.createSocket()) {
            s.bind(new InetSocketAddress(hostname, 0));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
