/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.it.ipc.sample;

import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientException;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;
import com.aws.iot.evergreen.ipc.services.lifecycle.exceptions.LifecycleIPCException;

import java.io.IOException;

public final class SampleIpcAwareServiceMovesToRunning {
    private SampleIpcAwareServiceMovesToRunning() {
    }

    public static void main(String[] args)
            throws InterruptedException, IPCClientException, LifecycleIPCException, IOException {
        IPCClient client = new IPCClientImpl(KernelIPCClientConfig.builder().build());
        LifecycleImpl c = new LifecycleImpl(client);
        c.reportState("RUNNING");
        Thread.sleep(1000);
        client.disconnect();
    }
}
