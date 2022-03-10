/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android;

public interface AndroidComponentControl {
    /**
     * Start component, bind service, waiting for first response for time out.
     *  After that wait until component terminates.
     *
     * @param msTimeout timeout for first response from component
     * @return exitCode of component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    int run(long msTimeout) throws RuntimeException, InterruptedException;

    /**
     * Start component, bind service, waiting for first response for time out.
     *  Update internal state for future shutdown.

     * @param msTimeout timeout for first response from component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    public void startup(long msTimeout) throws RuntimeException, InterruptedException;

    /**
     * Shutdown component.
     *
     * @param msTimeout timeout for response from component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    void shutdown(long msTimeout) throws RuntimeException, InterruptedException;
}
