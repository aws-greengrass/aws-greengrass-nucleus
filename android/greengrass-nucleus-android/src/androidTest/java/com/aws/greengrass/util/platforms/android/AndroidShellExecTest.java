/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.Test;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidShellExecTest {

    @Test
    void Given_exec_WHEN_commands_executed_using_static_methods_THEN_success() throws InterruptedException, IOException {
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            final String command = "pwd";
            String s = exec.cmd(command);
            assertFalse(s.contains("\n"));
            assertTrue(s.startsWith("/"));
            assertEquals(s, exec.sh(command));
            String s2 = exec.sh("ifconfig -a;echo Hello");
            assertTrue(s2.contains("Hello"));
            assertTrue(exec.successful(false, command));
        }
    }

    @Test
    void GIVEN_exec_WHEN_command_executed_in_background_THEN_success() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<String> stdoutMessages = new ArrayList<>();
        List<String> stderrMessages = new ArrayList<>();

        Platform.getInstance().createNewProcessRunner().withShell("echo hello")
                .withOut(str -> stdoutMessages.add(str.toString()))
                .withErr(str -> stderrMessages.add(str.toString()))
                .background(exc -> done.countDown());
        // Wait for 1 second for command to finish
        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(0, stderrMessages.size());
        assertEquals(1, stdoutMessages.size());
        assertTrue(stdoutMessages.get(0).startsWith("hello"));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_exec_WHEN_running_command_closed_THEN_success() throws IOException, InterruptedException {
        // close waits for atmost 7 seconds before close
        String command = "sleep 10";
        CountDownLatch done = new CountDownLatch(1);
        Exec exec = Platform.getInstance().createNewProcessRunner();

        exec.withShell(command).background(exc -> done.countDown());
        assertTrue(exec.isRunning());

        exec.close();
        assertFalse(exec.isRunning());
        // closing again should be no op, it should not throw
        exec.close();
        assertFalse(exec.isRunning());
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_exec_WHEN_command_outputs_THEN_output_captured() throws InterruptedException, IOException {
        Exec exec = Platform.getInstance().createNewProcessRunner();
        String expectedOutput = "HELLO";
        String command = "echo " + expectedOutput;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Consumer<CharSequence> stdoutConsumer = stdout::append;
        Consumer<CharSequence> stderrConsumer = stderr::append;
        exec.withShell(command).withOut(stdoutConsumer).withErr(stderrConsumer);
        assertTrue(exec.successful(false));
        // new line for shell
        assertEquals(expectedOutput.length() + System.lineSeparator().length(), stdout.length());
        assertEquals(0, stderr.length());

        // reinit consumers
        stdout.setLength(0);
        stderr.setLength(0);

        String stdErrCommand = command + " 1>&2";
        exec.withShell(stdErrCommand);
        assertFalse(exec.successful(false));
        assertEquals(0, stdout.length());
        // new line for shell and 1 more for windows because it actually includes the trailing space before the 1>&2
        assertEquals(expectedOutput.length() + System.lineSeparator().length() + (PlatformResolver.isWindows ? 1 : 0),
                stderr.length());
        exec.close();
    }

    @Test
    void GIVEN_exec_WHEN_stringfied_THEN_success() {
        String fakeCommand = "THIS IS FAKE COMMAND";
        assertEquals(String.format("[\"%s\"]", fakeCommand),
                Platform.getInstance().createNewProcessRunner().withExec(fakeCommand).toString());
    }

    @Test
    void GIVEN_exec_WHEN_stringfied_more_90_symbols_THEN_failure() {
        String fakeCommand = "=====================this is an fake command longer than 91 characters=====================";
        assertNotEquals(String.format("[\"%s\"]", fakeCommand),
                Platform.getInstance().createNewProcessRunner().withExec(fakeCommand).toString());
    }
}
