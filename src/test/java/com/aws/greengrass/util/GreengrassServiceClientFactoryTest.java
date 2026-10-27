/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(GGExtension.class)
class GreengrassServiceClientFactoryTest {
    private final IllegalStateException failure = new IllegalStateException("Connection pool shut down");
    private final AtomicReference<Runnable> onDelegateClose = new AtomicReference<>(() -> {
    });
    private final SdkHttpClient client =
            new GreengrassServiceClientFactory.CloseAwareHttpClient(new FailingHttpClient());

    @Test
    void GIVEN_client_not_closed_WHEN_request_throws_IllegalStateException_THEN_it_is_rethrown() {
        ExecutableHttpRequest request = client.prepareRequest(request());

        assertSame(failure, assertThrows(IllegalStateException.class, request::call));
    }

    @Test
    void GIVEN_request_fails_while_client_is_closing_WHEN_call_THEN_IOException_caused_by_the_failure() {
        ExecutableHttpRequest request = client.prepareRequest(request());
        AtomicReference<IOException> thrown = new AtomicReference<>();
        onDelegateClose.set(() -> thrown.set(assertThrows(IOException.class, request::call)));

        client.close();

        assertSame(failure, thrown.get().getCause());
    }

    private static HttpExecuteRequest request() {
        return HttpExecuteRequest.builder().request(SdkHttpRequest.builder().method(SdkHttpMethod.GET)
                .uri(URI.create("https://localhost")).build()).build();
    }

    private class FailingHttpClient implements SdkHttpClient {
        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    throw failure;
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public void close() {
            onDelegateClose.get().run();
        }
    }
}
