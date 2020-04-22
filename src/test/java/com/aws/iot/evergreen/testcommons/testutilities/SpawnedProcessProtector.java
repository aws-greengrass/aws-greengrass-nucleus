/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.testcommons.testutilities;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.Processes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("PMD.SystemPrintln")
public class SpawnedProcessProtector implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        String[] cmd = new String[]{"pgrep", "-P", String.valueOf(PidUtil.getMyPid())};
        Process proc = Runtime.getRuntime().exec(cmd);
        assertTrue(proc.waitFor(5, TimeUnit.SECONDS), "Able to run pgrep and find child processes");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            List<String> childPids = br.lines().collect(Collectors.toList());

            if (!childPids.isEmpty()) {
                System.err.println("Not all child PIDs were stopped before the test ended!");
                System.err.println("Going to try killing them for you, but this is a problem which must be fixed");
                System.err.println("PIDs: " + childPids);

                for (String pid : childPids) {
                    Processes.newPidProcess(Integer.parseInt(pid)).destroyForcefully();
                }

                fail("Child PIDs not all cleaned up: " + childPids.toString()
                        + ".\n Processes not killed or kernel not shutdown.");
            }
        }
    }
}
