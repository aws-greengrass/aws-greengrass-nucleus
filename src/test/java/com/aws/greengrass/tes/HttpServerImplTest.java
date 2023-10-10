/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class HttpServerImplTest {
    private static final String mockResponse = "Hello World";

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    @Mock
    private HttpHandler mockHttpHandler;

    private HttpServerImpl startServer(int port) {
        HttpServerImpl server = null;
        try {
            server = new HttpServerImpl(port, mockHttpHandler, executorService);
            server.start();
        } catch (IOException e) {
            fail("Could not start the server: {}", e);
        }
        return server;
    }

    private void stopServer(HttpServerImpl server) {
        server.stop();
        executorService.shutdown();
    }

    @SuppressWarnings("PMD.CloseResource")
    @ParameterizedTest
    @ValueSource(ints = {0, 1025, 65355})
    void GIVEN_port_WHEN_server_started_THEN_requests_are_successful(int port) throws Exception {
        HttpServerImpl server = startServer(port);
        try {
            doAnswer(invocationArgs -> {
                HttpExchange args = (HttpExchange) invocationArgs.getArguments()[0];
                args.sendResponseHeaders(HttpURLConnection.HTTP_OK, mockResponse.length());
                args.getResponseBody().write(mockResponse.getBytes());
                args.close();
                return null; //void method
            }).when(mockHttpHandler).handle(any());
            if (port == 0) {
                port = server.getServerPort();
            }
            URL url = new URL("http://localhost:" + port + HttpServerImpl.URL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setDoOutput(true);
            OutputStream out = con.getOutputStream();
            out.flush();
            out.close();

            InputStream ip = con.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(ip));

            StringBuilder response = new StringBuilder();
            String responseSingle = br.readLine();
            while (responseSingle != null) {
                response.append(responseSingle);
                responseSingle = br.readLine();
            }
            assertEquals(mockResponse, response.toString());
        } finally {
            stopServer(server);
        }
    }
}
