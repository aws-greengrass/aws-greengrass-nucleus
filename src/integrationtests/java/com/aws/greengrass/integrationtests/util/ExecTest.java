/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.util;

import com.aws.greengrass.util.Exec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecTest {
    @Test
    void Given_exec_WHEN_commands_executed_using_static_methods_THEN_success() throws InterruptedException, IOException {
        if (Exec.isWindows) {
            return;
        }
        final String command = "pwd";
        String s = Exec.cmd(command);
        assertFalse(s.contains("\n"));
        assertTrue(s.startsWith("/"));
        assertEquals(s, Exec.sh(command));
        String s2 = Exec.sh("ifconfig -a;echo Hello");
        assertTrue(s2.contains("Hello"));
        String expectedDir = System.getProperty("user.home");
        assertEquals(expectedDir, Exec.sh(new File(expectedDir), command));
        assertEquals(expectedDir, Exec.sh(Paths.get(expectedDir), command));
        assertTrue(Exec.successful(false, command));
    }

    @Test
    void GIVEN_exec_WHEN_command_executed_in_background_THEN_success() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<String> stdoutMessages = new ArrayList<>();
        List<String> stderrMessages = new ArrayList<>();

        new Exec().withShell("echo hello")
                .withOut(str -> stdoutMessages.add(str.toString()))
                .withErr(str -> stderrMessages.add(str.toString()))
                .background(exc -> done.countDown());
        // Wait for 1 second for command to finish
        assertTrue(done.await(2, TimeUnit.SECONDS));
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
        Exec exec = new Exec();
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
        Exec exec = new Exec();
        String expectedOutput = "HELLO";
        String command = "echo " + expectedOutput;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Consumer<String> stdoutConsumer = stdout::append;
        Consumer<String> stderrConsumer = stderr::append;
        exec.withShell(command).withOut(stdoutConsumer).withErr(stderrConsumer);
        assertTrue(exec.successful(false));
        // new line for shell
        assertEquals(expectedOutput.length(), stdout.length());
        assertEquals(0, stderr.length());

        // reinit consumers
        stdout.setLength(0);
        stderr.setLength(0);

        String stdErrCommand = command + " 1>&2";
        exec.withShell(stdErrCommand);
        assertFalse(exec.successful(false));
        assertEquals(0, stdout.length());
        // new line for shell and 1 more for windows because it actually includes the trailing space before the 1>&2
        assertEquals(expectedOutput.length() + (Exec.isWindows ? 1 : 0), stderr.length());
        exec.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_exec_WHEN_changing_directories_THEN_success() throws InterruptedException, IOException {
        final Exec exec = new Exec();
        final String getWorkingDirCmd = Exec.isWindows ? "cd" : "pwd";

        // By default Exec uses home as current directory for exec
        Path expectedDir = Paths.get(System.getProperty("user.dir"));
        String defaultDir = exec.withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(0, expectedDir.compareTo(Paths.get(defaultDir)));

        // Now change it to some other directory
        expectedDir = Paths.get("/").toAbsolutePath();
        String changedDir = exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(0, expectedDir.compareTo(Paths.get(changedDir)));

        // Now use the file argument to change into another directory again
        // File argument would use the current directory ("/") as base
        expectedDir = Paths.get(System.getProperty("user.home")).toAbsolutePath();
        changedDir = exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(0, expectedDir.compareTo(Paths.get(changedDir)));

        // Now change it to root again
        expectedDir = Paths.get("/").toAbsolutePath();
        changedDir = exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(0, expectedDir.compareTo(Paths.get(changedDir)));

        // by default cd change to home directory
        expectedDir = Paths.get(System.getProperty("user.home"));
        changedDir = exec.cd(/* no argument */).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(0, expectedDir.compareTo(Paths.get(changedDir)));
        exec.close();
    }

    @Test
    void GIVEN_exec_WHEN_stringfied_THEN_success() {
        // TODO: length of 90 as per the class does not seem to work
        String fakeCommand = "THIS IS FAKE COMMAND";
        assertEquals(String.format("[\"%s\"]", fakeCommand), new Exec().withExec(fakeCommand).toString());
    }

}
