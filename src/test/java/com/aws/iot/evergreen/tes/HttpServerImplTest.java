/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.any;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class HttpServerImplTest {
    private static final int serverPort = 6665;
    private static final String mockResponse = "Hello World";
    private HttpServerImpl server;

    @Mock
    private HttpHandler mockHttpHandler;

    @BeforeEach
    public void init() {
        try {
            server = new HttpServerImpl(serverPort, mockHttpHandler);
            server.start();
        } catch (IOException e) {
            fail("Could not start the server: {}", e);
        }
    }

    @AfterEach
    public void stop() {
        server.stop();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_port_WHEN_server_started_THEN_requests_are_successful() throws Exception {
        doAnswer(invocationArgs -> {
                HttpExchange args = (HttpExchange)invocationArgs.getArguments()[0];
                args.sendResponseHeaders(HttpURLConnection.HTTP_OK, mockResponse.length());
                args.getResponseBody().write(mockResponse.getBytes());
                args.close();
                return null; //void method
        }).when(mockHttpHandler).handle(any());
        URL url = new URL("http://localhost:" + serverPort + HttpServerImpl.URL);
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
    }
}
