/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.util;

import com.aws.iot.evergreen.util.Exec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
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
        assertEquals(expectedOutput.length() + System.lineSeparator().length() + (Exec.isWindows ? 1 : 0),
                stderr.length());
        exec.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_exec_WHEN_changing_directories_THEN_success() throws InterruptedException, IOException {
        Exec exec = new Exec();
        String getWorkingDirCmd = "pwd";
        if (Exec.isWindows) {
            getWorkingDirCmd = "echo %cd%";
        }
        // By default Exec uses home current directory for exec
        String expectedDir = System.getProperty("user.dir");
        String defaultDir = exec.withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(expectedDir, defaultDir);

        // Now change it to some other directory
        // TODO: Change this to a proper root to work on all platforms
        expectedDir = "/";
        File expectedDirFile = Paths.get(expectedDir).toAbsolutePath().toFile();
        String changedDir = exec.cd(expectedDirFile).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(expectedDirFile.toPath().toAbsolutePath().toString(), changedDir);

        // Now use the file argument to change into another directory again
        // File argument would use the current directory ("/") as base
        expectedDir = System.getProperty("user.home");
        changedDir = exec.cd(expectedDir).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(expectedDir, changedDir);

        // Now change it to root again
        changedDir = exec.cd(expectedDirFile).withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(expectedDirFile.toString(), changedDir);

        // by default cd change to home directory
        expectedDir = System.getProperty("user.home");
        changedDir = exec.cd().withShell(getWorkingDirCmd).execAndGetStringOutput();
        assertEquals(expectedDir, changedDir);
        exec.close();
    }

    @Test
    void GIVEN_exec_WHEN_stringfied_THEN_success() {
        // TODO: length of 90 as per the class does not seem to work
        String fakeCommand = "THIS IS FAKE COMMAND";
        assertEquals(String.format("[\"%s\"]", fakeCommand), new Exec().withExec(fakeCommand).toString());
    }

}
