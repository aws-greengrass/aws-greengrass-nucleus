/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class HttpServerImplTest {

    private static final String MOCK_CREDENTIAL_RESPONSE = "Hello World";

    ExecutorService executorService = TestUtils.synchronousExecutorService();

    HttpHandler mockCredentialRequestHandler = exchange -> {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, MOCK_CREDENTIAL_RESPONSE.length());
        exchange.getResponseBody().write(MOCK_CREDENTIAL_RESPONSE.getBytes());
        exchange.close();
    };

    HttpServerImpl server;

    int withServer(int port) throws IOException {
        server = new HttpServerImpl(port, mockCredentialRequestHandler, executorService);
        server.start();
        return server.getServerPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1025, 65355})
    void GIVEN_port_WHEN_server_started_THEN_requests_are_successful(int port) throws Exception {
        Set<String> hostAddresses = getLoopbackAddresses().stream()
                .map(InetAddress::getHostAddress)
                .collect(Collectors.toSet());

        int resolvedPort = withServer(port);

        for (String host : hostAddresses) {
            String tesResponse = sendTESRequest(host, resolvedPort);
            assertEquals(MOCK_CREDENTIAL_RESPONSE, tesResponse);
        }
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

    private static Set<InetAddress> getLoopbackAddresses() throws IOException {
        Set<InetAddress> loopbackAddresses = new HashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface nif = interfaces.nextElement();
            if (!nif.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addresses = nif.getInetAddresses();
            while (addresses.hasMoreElements()) {
                loopbackAddresses.add(addresses.nextElement());
            }
        }
        return loopbackAddresses;
    }
}
