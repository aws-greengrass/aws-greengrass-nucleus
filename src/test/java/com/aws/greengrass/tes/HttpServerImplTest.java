/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.SocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({MockitoExtension.class, GGExtension.class})
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class HttpServerImplTest {

    private static final String MOCK_CREDENTIAL_RESPONSE = "Hello World";
    private static final String[] hosts = {"localhost", "127.0.0.1", "::1"};

    ExecutorService executorService = TestUtils.synchronousExecutorService();

    HttpHandler mockCredentialRequestHandler = exchange -> {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, MOCK_CREDENTIAL_RESPONSE.length());
        exchange.getResponseBody().write(MOCK_CREDENTIAL_RESPONSE.getBytes());
        exchange.close();
    };

    HttpServerImpl server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        executorService.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1025, 65355})
    void GIVEN_port_WHEN_server_started_THEN_requests_are_successful(int port) throws Exception {
        int resolvedPort = startRealHttpServer(port);
        for (String host : hosts) {
            String tesResponse = sendTESRequest(host, resolvedPort);
            assertEquals(MOCK_CREDENTIAL_RESPONSE, tesResponse);
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> providesPortBindFailureModes() {
        return Stream.of(
                // fail when first of two servers is being created
                Arguments.of(Mode.PORT_BIND_FAILS_FIRST_TIME_ONLY),
                // fail when second of two servers is being created
                Arguments.of(Mode.PORT_BIND_FAILS_SECOND_TIME_ONLY)
        );
    }

    @ParameterizedTest
    @MethodSource("providesPortBindFailureModes")
    void GIVEN_port_already_in_use_WHEN_fake_server_started_on_customer_picked_port_THEN_exception_thrown(Mode mode) {
        assertThrows(SocketException.class, () -> startFakeHttpServer(1234, mode));
    }

    @ParameterizedTest
    @MethodSource("providesPortBindFailureModes")
    void GIVEN_port_already_in_use_WHEN_fake_server_started_on_system_picked_port_THEN_server_creation_retried(
            Mode mode, ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, SocketException.class);
        startFakeHttpServer(0, mode);
        List<InetSocketAddress> serverAddresses = server.getServerAddresses();
        assertFalse(serverAddresses.isEmpty());
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> providesExpectedServerAddressTypeForMode() {
        return Stream.of(
                Arguments.of(Mode.IPV6_ONLY, Inet6Address.class),
                Arguments.of(Mode.IPV4_ONLY, Inet4Address.class)
        );
    }

    @ParameterizedTest
    @MethodSource("providesExpectedServerAddressTypeForMode")
    void GIVEN_only_one_ip_family_enabled_WHEN_fake_server_started_THEN_only_one_server_created(
            Mode mode, Class<? extends InetAddress> expectedServerAddressType,
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, SocketException.class);

        startFakeHttpServer(0, mode);

        List<InetSocketAddress> serverAddresses = server.getServerAddresses();
        assertFalse(serverAddresses.isEmpty());
        assertTrue(serverAddresses.stream().map(InetSocketAddress::getAddress)
                .allMatch(addr -> expectedServerAddressType.isAssignableFrom(addr.getClass())));
    }

    /**
     * Starts a real HTTP server.
     *
     * @param port port
     * @return resolved port
     * @throws IOException if server is not able to start
     */
    private int startRealHttpServer(int port) throws IOException {
        server = new HttpServerImpl(port, mockCredentialRequestHandler, executorService);
        server.start();
        return server.getServerPort();
    }

    /**
     * Starts a fake HTTP server.
     *
     * @param port port
     * @param mode how the fake server will operate
     * @return port resolved port
     * @throws IOException if server is not able to start
     */
    private int startFakeHttpServer(int port, Mode mode) throws IOException {
        server = new HttpServerImpl(port, mockCredentialRequestHandler, executorService,
                new FakeHttpServerProvider(mode), new FakeSocketFactory(mode));
        server.start();
        return server.getServerPort();
    }

    private static String sendTESRequest(String host, int port) throws IOException {
        URL url = new URL("http", host, port, HttpServerImpl.URL);

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setDoOutput(true);

        try (OutputStream out = con.getOutputStream()) {
            out.flush();
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    enum Mode {
        IPV4_AND_IPV6,
        /**
         * Simulates a system where only IPv6 is available.
         * The server will not (fake) bind to any IPv4 addresses
         */
        IPV6_ONLY,
        /**
         * Simulates a system where only IPv4 is available.
         * The server will not (fake) bind to any IPv6 addresses
         */
        IPV4_ONLY,
        /**
         * The first attempt to create an HTTP server fails,
         * due to a simulated port binding issue.
         *
         * <p>Subsequent attempts will succeed.
         */
        PORT_BIND_FAILS_FIRST_TIME_ONLY,
        /**
         * The second attempt to create an HTTP server fails,
         * due to a simulated port binding issue.
         *
         * <p>Previous and subsequent attempts will succeed.
         */
        PORT_BIND_FAILS_SECOND_TIME_ONLY,
    }

    @RequiredArgsConstructor
    static class FakeHttpServerProvider extends HttpServerProvider {
        private final Mode mode;
        private final AtomicInteger portBindFailures = new AtomicInteger();

        @Override
        public HttpServer createHttpServer(InetSocketAddress addr, int backlog) throws IOException {
            return new FakeHttpServer(mode, addr, portBindFailures);
        }

        @Override
        public HttpsServer createHttpsServer(InetSocketAddress addr, int backlog) {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeHttpServer extends HttpServer {
        private final InetSocketAddress addr;

        @SuppressWarnings("PMD.CallSuperInConstructor")
        public FakeHttpServer(Mode mode, InetSocketAddress addr, AtomicInteger portBindFailures) throws IOException {
            if (mode == Mode.PORT_BIND_FAILS_FIRST_TIME_ONLY && portBindFailures.getAndIncrement() == 0) {
                throw new SocketException("Port already bound");
            }
            if (mode == Mode.PORT_BIND_FAILS_SECOND_TIME_ONLY) {
                if (portBindFailures.incrementAndGet() == 2) {
                    throw new SocketException("Port already bound");
                }
            }
            if (mode == Mode.IPV6_ONLY && addr.getAddress() instanceof Inet4Address) {
                throw new SocketException("IPv4 not supported");
            }
            if (mode == Mode.IPV4_ONLY && addr.getAddress() instanceof Inet6Address) {
                throw new SocketException("IPv6 not available");
            }
            this.addr = addr;
        }

        @Override
        public InetSocketAddress getAddress() {
            return addr;
        }

        @Override
        public void bind(InetSocketAddress addr, int backlog) {
        }

        @Override
        public void start() {
        }

        @Override
        public void setExecutor(Executor executor) {
        }

        @Override
        public Executor getExecutor() {
            return null;
        }

        @Override
        public void stop(int delay) {
        }

        @Override
        public HttpContext createContext(String path, HttpHandler handler) {
            return null;
        }

        @Override
        public HttpContext createContext(String path) {
            return null;
        }

        @Override
        public void removeContext(String path) {
        }

        @Override
        public void removeContext(HttpContext context) {
        }
    }

    @RequiredArgsConstructor
    static class FakeSocketFactory extends SocketFactory {
        private final Mode mode;

        @Override
        public Socket createSocket() {
            return new FakeSocket(mode);
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }
    }

    @RequiredArgsConstructor
    static class FakeSocket extends Socket {
        private final Mode mode;

        @Override
        public void bind(SocketAddress bindpoint) throws IOException {
            if (!(bindpoint instanceof InetSocketAddress)) {
                throw new UnsupportedOperationException();
            }
            InetSocketAddress addr = (InetSocketAddress) bindpoint;

            if (mode == Mode.IPV6_ONLY && addr.getAddress() instanceof Inet4Address) {
                throw new SocketException("IPv4 not available");
            }

            if (mode == Mode.IPV4_ONLY && addr.getAddress() instanceof Inet6Address) {
                throw new SocketException("IPv6 not available");
            }
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
