/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc.sample;

import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;

public class SampleIpcAwareServiceMovesToRunning {
    public static void main(String[] args) throws Exception {
        IPCClient client = new IPCClientImpl(KernelIPCClientConfig.builder().build());
        LifecycleImpl c = new LifecycleImpl(client);
        c.reportState("RUNNING");
        client.disconnect();
    }
}
