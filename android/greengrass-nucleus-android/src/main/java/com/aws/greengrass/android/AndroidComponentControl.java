/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android;

public interface AndroidComponentControl {
    /**
     * Start component, bind service, waiting for first response for time out.
     *  Update internal state for future shutdown.

     * @param msTimeout timeout for first response from component
     * @throws InterruptedException when current thread has been interrupted
     */
    void startup(long msTimeout) throws InterruptedException;

    /**
     * Wait for component completion.
     *
     * @return exit code of component.
     * @throws InterruptedException when current thread has been interrupted
     */
    int waitCompletion() throws InterruptedException;

    /**
     * Shutdown component.
     *
     * @param msTimeout timeout for response from component
     * @throws InterruptedException when current thread has been interrupted
     */
    void shutdown(long msTimeout) throws InterruptedException;
}
