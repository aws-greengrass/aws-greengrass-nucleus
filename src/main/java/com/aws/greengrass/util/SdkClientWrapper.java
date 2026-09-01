/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import org.apache.http.NoHttpResponseException;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.net.SocketException;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SdkClientWrapper<T extends SdkClient> {
    private static final Logger logger = LogManager.getLogger(SdkClientWrapper.class);

    private volatile T client;
    private final Supplier<T> clientFactory;
    private final Lock lock = LockFactory.newReentrantLock(this);

    public SdkClientWrapper(Supplier<T> clientFactory) {
        this.clientFactory = clientFactory;
        this.client = clientFactory.get();
    }

    /**
     * Executes the given operation on the client, handling potential SDK client exceptions.
     *
     * <p>This method applies the provided operation to the client. If an {@link SdkClientException}
     * occurs and the client needs refreshing (as determined by {@link #shouldRefreshClient(SdkClientException)}),
     * it will attempt to refresh the client and retry the operation once.</p>
     *
     * @param <R> The return type of the operation
     * @param operation A function that takes the client of type T and returns a result of type R
     * @return The result of the operation
     * @throws SdkClientException If the operation fails and the client cannot be refreshed or fails after refresh
     * @throws RuntimeException If an unexpected error occurs during execution
     */
    public <R> R execute(final Function<T, R> operation) {
        try {
            return operation.apply(client);
        } catch (SdkClientException e) {
            if (shouldRefreshClient(e)) {
                logger.atDebug().log("Client needs refresh due to: {}", e.getMessage());
                try {
                    refreshClient();
                    return operation.apply(client);
                } catch (SdkClientException retryException) {
                    logger.atError().log("Failed to execute operation after client refresh", retryException);
                    throw retryException;
                }
            }
            logger.atError().log("SDK client operation failed", e);
            throw e;
        }
    }

    private void refreshClient() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (client != null) {
                try {
                    client.close();
                } catch (SdkClientException e) {
                    logger.atError().log("Error closing client: " + e.getMessage());
                }
            }
            // Creates new client when refresh needed
            client = clientFactory.get();
        }
    }

    private boolean shouldRefreshClient(SdkClientException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketException && "Connection reset".equals(cause.getMessage())) {
                return true;
            }
            if (cause instanceof NoHttpResponseException) {
                return true;
            }
            // Add other conditions that should trigger a client refresh here
            cause = cause.getCause();
        }
        return false;
    }
}
