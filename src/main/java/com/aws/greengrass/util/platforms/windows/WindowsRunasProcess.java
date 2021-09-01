/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.sun.jna.platform.win32.WinBase;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Setter;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Manage a Windows process running as a specific user.
 */
@SuppressWarnings("PMD")  // TODO re-enable warnings
@SuppressFBWarnings
public class WindowsRunasProcess extends Process {
    private final String domain;
    private final String username;
    @Setter
    private String lpEnvironment;  // env vars in lpEnvironment format
    @Setter
    private String lpCurrentDirectory;

    private WinBase.PROCESS_INFORMATION procInfo;
    private boolean hasExited;
    private int exitCode;

    private InputStream inputStream;    // for reading child process stdout
    private InputStream errorStream;    // for reading child process stderr
    private OutputStream outputStream;  // for writing to child process stdin

    /**
     * Setup to run a Windows process as a specific user.
     * @param domain name of domain that contains the user account
     * @param username name of the account
     */
    public WindowsRunasProcess(String domain, String username) {
        this.domain = domain;
        this.username = username;
    }

    public void start(String command) {
        // TODO get credential for the domain/user
        // TODO CreateProcessWithLogonW then in a different thread: WaitForSingleObject, GetExitCodeProcess
        // reference UNIXProcess.initStreams
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
        return errorStream;
    }

    @Override
    public synchronized int waitFor() throws InterruptedException {
        while (!hasExited) {
            wait();
        }
        return exitCode;
    }

    @Override
    public synchronized int exitValue() {
        if (!hasExited) {
            throw new IllegalThreadStateException("process hasn't exited");
        }
        return exitCode;
    }

    @Override
    public void destroy() {
        // TODO call TerminateProcess for forceful shutdown
    }
}
