/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class GreengrassServiceClientFactoryTest {
    private static final String SERVED_BY = "served-by";
    private final IllegalStateException poolShutDown = new IllegalStateException("Connection pool shut down");
    private final AtomicReference<String> config = new AtomicReference<>("initial");
    private final AtomicReference<TLSAuthException> nextBuildFailure = new AtomicReference<>();
    private final AtomicReference<Runnable> onCall = new AtomicReference<>(() -> {
    });
    private final AtomicReference<Runnable> onDelegateClose = new AtomicReference<>(() -> {
    });
    private final List<FakeHttpClient> built = new ArrayList<>();
    private final GreengrassServiceClientFactory.SwappableHttpClient client =
            new GreengrassServiceClientFactory.SwappableHttpClient(() -> {
                TLSAuthException failure = nextBuildFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
                FakeHttpClient delegate = new FakeHttpClient(config.get());
                built.add(delegate);
                return delegate;
            });
    private final Context context = new Context();

    @Mock
    private DeviceConfiguration deviceConfiguration;

    @AfterEach
    void cleanup() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_client_retired_WHEN_next_request_THEN_it_runs_on_a_new_client_built_from_the_config_at_that_time()
            throws IOException {
        assertEquals("initial", servedBy(client.prepareRequest(request()).call()));
        config.set("half written");
        client.retire();
        config.set("complete");

        assertEquals("complete", servedBy(client.prepareRequest(request()).call()));
        assertTrue(built.get(0).closed);
    }

    @Test
    void GIVEN_client_not_retired_WHEN_request_throws_IllegalStateException_THEN_it_is_rethrown() {
        onCall.set(() -> {
            throw poolShutDown;
        });
        ExecutableHttpRequest request = client.prepareRequest(request());

        assertSame(poolShutDown, assertThrows(IllegalStateException.class, request::call));
    }

    @Test
    void GIVEN_request_fails_while_client_is_retired_WHEN_call_THEN_IOException_caused_by_the_failure() {
        onCall.set(() -> {
            throw poolShutDown;
        });
        ExecutableHttpRequest request = client.prepareRequest(request());
        AtomicReference<IOException> thrown = new AtomicReference<>();
        onDelegateClose.set(() -> thrown.set(assertThrows(IOException.class, request::call)));

        client.retire();

        assertSame(poolShutDown, thrown.get().getCause());
    }

    @Test
    void GIVEN_building_the_client_fails_once_WHEN_sdk_call_THEN_sdk_retries_it_on_a_new_client() {
        nextBuildFailure.set(new TLSAuthException("Failed to get trust manager"));
        try (GreengrassV2DataClient sdkClient = sdkClient(client, URI.create("https://localhost"))) {
            sdkClient.listThingGroupsForCoreDevice(r -> r.coreDeviceThingName("thing"));
        }

        assertEquals(1, built.size());
    }

    @Test
    void GIVEN_apache_client_retired_after_request_is_prepared_WHEN_sdk_call_THEN_sdk_retries_it_on_a_new_client()
            throws IOException {
        AtomicInteger serverRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            serverRequests.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        AtomicInteger builds = new AtomicInteger();
        AtomicReference<GreengrassServiceClientFactory.SwappableHttpClient> apache = new AtomicReference<>();
        apache.set(new GreengrassServiceClientFactory.SwappableHttpClient(() -> builds.incrementAndGet() == 1
                ? new RetiredBeforeCallHttpClient(ApacheHttpClient.create(), () -> apache.get().retire())
                : ApacheHttpClient.create()));
        URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort());

        try (GreengrassV2DataClient sdkClient = sdkClient(apache.get(), endpoint)) {
            sdkClient.listThingGroupsForCoreDevice(r -> r.coreDeviceThingName("thing"));
        } finally {
            apache.get().close();
            server.stop(0);
        }

        assertEquals(2, builds.get());
        assertEquals(1, serverRequests.get());
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_cert_path_change_WHEN_fetch_THEN_same_sdk_client_is_returned() throws Exception {
        GreengrassServiceClientFactory factory = factory();
        GreengrassV2DataClient before = factory.fetchGreengrassV2DataClient();

        configListener().childChanged(WhatHappened.childChanged,
                Topic.of(context, DEVICE_PARAM_ROOT_CA_PATH, "/new/rootCA.pem"));

        assertSame(before, factory.fetchGreengrassV2DataClient());
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_region_change_WHEN_fetch_THEN_new_sdk_client_is_returned() throws Exception {
        GreengrassServiceClientFactory factory = factory();
        GreengrassV2DataClient before = factory.fetchGreengrassV2DataClient();

        configListener().childChanged(WhatHappened.childChanged,
                Topic.of(context, DEVICE_PARAM_AWS_REGION, "us-west-2"));

        assertNotSame(before, factory.fetchGreengrassV2DataClient());
    }

    @Test
    void GIVEN_root_ca_cannot_be_loaded_WHEN_fetch_THEN_TLSAuthException() {
        when(deviceConfiguration.getRootCAFilePath())
                .thenReturn(Topic.of(context, DEVICE_PARAM_ROOT_CA_PATH, "/missing/rootCA.pem"));
        GreengrassServiceClientFactory factory = new GreengrassServiceClientFactory(deviceConfiguration);

        assertThrows(TLSAuthException.class, factory::fetchGreengrassV2DataClient);
    }

    private GreengrassServiceClientFactory factory() {
        when(deviceConfiguration.getEnvironmentStage()).thenReturn(Topic.of(context, "environmentStage", "prod"));
        when(deviceConfiguration.getGGDataEndpoint())
                .thenReturn(Topic.of(context, "greengrassDataPlaneEndpoint", "https://localhost:8443"));
        when(deviceConfiguration.getAWSRegion()).thenReturn(Topic.of(context, DEVICE_PARAM_AWS_REGION, "us-east-1"));
        return new GreengrassServiceClientFactory(deviceConfiguration);
    }

    private ChildChanged configListener() {
        ArgumentCaptor<ChildChanged> listener = ArgumentCaptor.forClass(ChildChanged.class);
        verify(deviceConfiguration).onAnyChange(listener.capture());
        return listener.getValue();
    }

    private static GreengrassV2DataClient sdkClient(SdkHttpClient httpClient, URI endpoint) {
        return GreengrassV2DataClient.builder().httpClient(httpClient)
                .credentialsProvider(AnonymousCredentialsProvider.create()).region(Region.US_EAST_1)
                .endpointOverride(endpoint).overrideConfiguration(c -> c.retryPolicy(RetryMode.STANDARD)).build();
    }

    private static HttpExecuteRequest request() {
        return HttpExecuteRequest.builder().request(SdkHttpRequest.builder().method(SdkHttpMethod.GET)
                .uri(URI.create("https://localhost")).build()).build();
    }

    private static String servedBy(HttpExecuteResponse response) {
        return response.httpResponse().firstMatchingHeader(SERVED_BY).orElse(null);
    }

    /**
     * A real Apache client whose first prepared request runs {@code beforeCall} first, so a retire lands between
     * prepareRequest and the connection lease.
     */
    private static class RetiredBeforeCallHttpClient implements SdkHttpClient {
        private final SdkHttpClient delegate;
        private final Runnable beforeCall;

        RetiredBeforeCallHttpClient(SdkHttpClient delegate, Runnable beforeCall) {
            this.delegate = delegate;
            this.beforeCall = beforeCall;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            ExecutableHttpRequest prepared = delegate.prepareRequest(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    beforeCall.run();
                    return prepared.call();
                }

                @Override
                public void abort() {
                    prepared.abort();
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private class FakeHttpClient implements SdkHttpClient {
        private final String builtFrom;
        private volatile boolean closed;

        FakeHttpClient(String builtFrom) {
            this.builtFrom = builtFrom;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    onCall.get().run();
                    if (closed) {
                        throw poolShutDown;
                    }
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder().statusCode(200).putHeader(SERVED_BY, builtFrom)
                                    .putHeader("Content-Type", "application/json").build())
                            .responseBody(AbortableInputStream.create(
                                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)))).build();
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public void close() {
            onDelegateClose.get().run();
            closed = true;
        }
    }
}
