/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.util.Exec;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.Processes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("PMD.SystemPrintln")
public class SpawnedProcessProtector implements AfterAllCallback, AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        List<String> childPids = getChildPids();
        if (!childPids.isEmpty()) {
            System.err.println(
                    "Child PID not cleaned after test case " + context.getDisplayName() + ". Child PIDs: " + String
                            .join(", ", childPids));
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        List<String> childPids = getChildPids();

        if (!childPids.isEmpty()) {
            System.err.println("Not all child PIDs were stopped before the test ended!");
            System.err.println("Going to try killing them for you, but this is a problem which must be fixed");
            System.err.println("PIDs: " + childPids);

            for (String pid : childPids) {
                // Use ps to get the command which is running so we can more easily identify the leaker.
                Process proc = new ProcessBuilder().command("ps", "-p", pid, "-o", "args").start();
                proc.waitFor(10, TimeUnit.SECONDS);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    br.lines().forEach(System.err::println);
                }

                // Kill the stray process
                Processes.newPidProcess(Integer.parseInt(pid)).destroyForcefully();
            }

            fail("Child PIDs not all cleaned up: " + childPids.toString()
                    + ".\n Processes not killed or kernel not shutdown.");
        }
    }

    private List<String> getChildPids() throws IOException, InterruptedException {
        if (Exec.isWindows) {
            return new ArrayList<>();
        }
        String[] cmd = {"pgrep", "-P", String.valueOf(PidUtil.getMyPid())};
        Process proc = Runtime.getRuntime().exec(cmd);
        assertTrue(proc.waitFor(5, TimeUnit.SECONDS), "Able to run pgrep and find child processes");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            return br.lines().collect(Collectors.toList());
        }
    }
}
