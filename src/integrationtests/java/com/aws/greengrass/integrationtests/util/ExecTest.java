/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.util;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecTest {

    private String readLink(String path) throws IOException {
        Path p = Paths.get(path);
        if (Files.isSymbolicLink(p)) {
            return Files.readSymbolicLink(p).toString();
        }
        return path;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void Given_exec_WHEN_commands_executed_using_static_methods_THEN_success() throws InterruptedException, IOException {
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            final String command = "pwd";
            String s = exec.cmd(command);
            assertFalse(s.contains("\n"));
            assertTrue(s.startsWith("/"));
            assertEquals(s, exec.sh(command));
            String s2 = exec.sh("ifconfig -a;echo Hello");
            assertTrue(s2.contains("Hello"));
            String expectedDir = readLink(System.getProperty("user.home"));
            assertEquals(expectedDir, exec.sh(new File(expectedDir), command));
            assertEquals(expectedDir, exec.sh(Paths.get(expectedDir), command));
            assertTrue(exec.successful(false, command));
        }
    }

    @Test
    @Disabled  // TODO re-enable when WindowsExec actually works
    @EnabledOnOs(OS.WINDOWS)
    void Given_windows_exec_WHEN_commands_executed_using_static_methods_THEN_success() throws InterruptedException,
            IOException {
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            final String command = "cmd /c cd";
            String s = exec.cmd(command);
            assertFalse(s.contains("\n"));
            // skip first character which should be the drive letter (C, D etc.)
            assertTrue(s.substring(1).startsWith(":\\"));
            assertEquals(s, exec.sh("cd"));
            // test changing the shell
            s = exec.usingShell("powershell").cmd("cd");
            assertTrue(s.contains("Path"));
            String s2 = exec.sh("echo Hello");
            assertTrue(s2.contains("Hello"));
            String expectedDir = readLink(System.getProperty("user.home"));
            assertEquals(expectedDir, exec.sh(new File(expectedDir), command));
            assertEquals(expectedDir, exec.sh(Paths.get(expectedDir), command));
            assertTrue(exec.successful(false, command));
        }
    }

    @Test
    @Disabled  // TODO re-enable when WindowsExec actually works. tested locally
    @EnabledOnOs(OS.WINDOWS)
    void GIVEN_windows_exec_WHEN_lookup_common_command_THEN_returns_correct_path() throws IOException {
        String expectedCmdPathStr = "C:\\Windows\\System32\\cmd.exe";
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            // absolute path
            assertThat(Objects.requireNonNull(exec.which("C:\\Windows\\System32\\cmd.exe")).toString(),
                    equalToIgnoringCase(expectedCmdPathStr));
            // absolute path without extension
            assertThat(Objects.requireNonNull(exec.which("C:\\Windows\\System32\\cmd")).toString(),
                    equalToIgnoringCase(expectedCmdPathStr));
            // forward slash
            assertThat(Objects.requireNonNull(exec.which("C:/Windows/System32/cmd.exe")).toString(),
                    equalToIgnoringCase(expectedCmdPathStr));
            // command only
            assertThat(Objects.requireNonNull(exec.which("cmd")).toString(),
                    equalToIgnoringCase(expectedCmdPathStr));
            assertNull(exec.which("nonexist_program"));
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
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_exec_WHEN_changing_directories_THEN_success() throws InterruptedException, IOException {
        final Exec exec = Platform.getInstance().createNewProcessRunner();
        final String getWorkingDirCmd = PlatformResolver.isWindows ? "cd" : "pwd";

        // resolve links in-case user.dir or user.home is a symlink

        // By default Exec uses home as current directory for exec
        Path expectedDir = Paths.get(readLink(System.getProperty("user.dir")));
        Path defaultDir = Paths.get(readLink(exec.withShell(getWorkingDirCmd).execAndGetStringOutput()));
        assertThat(expectedDir, is(defaultDir));

        // Now change it to some other directory
        expectedDir = Paths.get("/").toAbsolutePath();
        Path changedDir =
                Paths.get(exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput());
        assertThat(expectedDir, is(expectedDir));

        // Now use the file argument to change into another directory again
        // File argument would use the current directory ("/") as base
        expectedDir = Paths.get(readLink(System.getProperty("user.home"))).toAbsolutePath();
        changedDir = Paths.get(exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput());
        assertThat(changedDir, is(expectedDir));

        // Now change it to root again
        expectedDir = Paths.get("/").toAbsolutePath();
        changedDir = Paths.get(exec.cd(expectedDir.toString()).withShell(getWorkingDirCmd).execAndGetStringOutput());
        assertThat(changedDir, is(expectedDir));

        exec.close();
    }

    @Test
    void GIVEN_exec_WHEN_stringfied_THEN_success() {
        // GG_NEEDS_REVIEW: TODO: length of 90 as per the class does not seem to work
        String fakeCommand = "THIS IS FAKE COMMAND";
        assertEquals(String.format("[\"%s\"]", fakeCommand),
                Platform.getInstance().createNewProcessRunner().withExec(fakeCommand).toString());
    }

}
