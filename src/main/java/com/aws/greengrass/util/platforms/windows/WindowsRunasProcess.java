/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.exceptions.ProcessCreationException;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.sun.jna.platform.win32.Advapi32.LOGON_WITH_PROFILE;
import static com.sun.jna.platform.win32.WinBase.STILL_ACTIVE;

/**
 * Manage a Windows process running as a specific user.
 */
public class WindowsRunasProcess extends Process {
    private static final int PROCESS_CREATION_FLAGS = WinBase.CREATE_UNICODE_ENVIRONMENT  // use unicode
            | WinBase.CREATE_NO_WINDOW;  // don't create a window on desktop
    private static final int LOGON_FLAGS = LOGON_WITH_PROFILE;
    private static final char NULL_CHAR = '\0';
    public static final String SYSTEM_ROOT = "SystemRoot";
    public static final int EXIT_CODE_TERMINATED = 130;

    private final String domain;
    private final String username;
    private final Map<String, String> additionalEnv = new HashMap<>();
    private String currentDirectory;

    private WinBase.PROCESS_INFORMATION procInfo;

    private InputStream stdout;
    private InputStream stderr;
    private OutputStream stdin;

    private WinNT.HANDLEByReference inPipeReadHandle;
    private WinNT.HANDLEByReference inPipeWriteHandle;
    private WinNT.HANDLEByReference outPipeReadHandle;
    private WinNT.HANDLEByReference outPipeWriteHandle;
    private WinNT.HANDLEByReference errPipeReadHandle;
    private WinNT.HANDLEByReference errPipeWriteHandle;

    /**
     * Setup to run a Windows process as a specific user.
     * @param domain name of domain that contains the user account
     * @param username name of the account
     */
    public WindowsRunasProcess(String domain, String username) {
        super();
        this.domain = domain;
        this.username = username;
    }

    /**
     * Set env vars in addition to the user's own existing env vars.
     * @param envs a map of env vars to set
     */
    public void setAdditionalEnv(Map<String, String> envs) {
        additionalEnv.putAll(envs);
    }

    /**
     * Start the process as given user.
     * @param command command line to run
     * @throws IOException if failed to run subprocess
     */
    public void start(String command) throws IOException {
        initPipes();

        // Prepare password
        byte[] credBlob = WindowsCredUtils.read(domain, username);
        ByteBuffer bb = ByteBuffer.wrap(credBlob);
        CharBuffer cb = StandardCharsets.UTF_8.decode(bb);
        char[] password = new char[cb.length() + 1];
        cb.get(password, 0, cb.length());
        password[password.length - 1] = NULL_CHAR; // char[] needs to be null terminated for windows
        Arrays.fill(cb.array(), (char) 0);  // zero-out temporary buffers
        Arrays.fill(bb.array(), (byte) 0);

        // Start the process
        WinBase.STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
        startupInfo.dwFlags = WinBase.STARTF_USESTDHANDLES;
        startupInfo.hStdInput = inPipeReadHandle.getValue();
        startupInfo.hStdOutput = outPipeWriteHandle.getValue();
        startupInfo.hStdError = errPipeWriteHandle.getValue();
        startupInfo.write();

        boolean created;
        synchronized (this) {
            procInfo = new WinBase.PROCESS_INFORMATION();
            created = ProcAdvapi32.INSTANCE.CreateProcessWithLogonW(username, domain, password, LOGON_FLAGS, null,
                    command, PROCESS_CREATION_FLAGS, computeEnvironmentBlock(), currentDirectory, startupInfo,
                    procInfo);
            Arrays.fill(password, NULL_CHAR);  // zero-out password buffer immediately after use
        }

        if (!created) {
            LastErrorException cause = getLastErrorAsException();
            throw new ProcessCreationException(Kernel32Util.formatMessageFromLastErrorCode(cause.getErrorCode()));
        }

        initStreams();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void initPipes() throws IOException {
        inPipeReadHandle = new WinNT.HANDLEByReference();
        inPipeWriteHandle = new WinNT.HANDLEByReference();
        outPipeReadHandle = new WinNT.HANDLEByReference();
        outPipeWriteHandle = new WinNT.HANDLEByReference();
        errPipeReadHandle = new WinNT.HANDLEByReference();
        errPipeWriteHandle = new WinNT.HANDLEByReference();

        WinBase.SECURITY_ATTRIBUTES pipeSa = new WinBase.SECURITY_ATTRIBUTES();
        pipeSa.bInheritHandle = true;  // true otherwise streams are not piped
        pipeSa.lpSecurityDescriptor = null;
        pipeSa.write();

        // Create pipes
        if (!Kernel32.INSTANCE.CreatePipe(inPipeReadHandle, inPipeWriteHandle, pipeSa, 0)) {
            throw new IOException("Failed to create pipe", getLastErrorAsException());
        }
        if (!Kernel32.INSTANCE.CreatePipe(outPipeReadHandle, outPipeWriteHandle, pipeSa, 0)) {
            throw new IOException("Failed to create pipe", getLastErrorAsException());
        }
        if (!Kernel32.INSTANCE.CreatePipe(errPipeReadHandle, errPipeWriteHandle, pipeSa, 0)) {
            throw new IOException("Failed to create pipe", getLastErrorAsException());
        }
    }

    @SuppressWarnings("PMD.AvoidFileStream")
    private void initStreams() throws ProcessCreationException {
        FileDescriptor stdinFd = new FileDescriptor();
        FileDescriptor stdoutFd = new FileDescriptor();
        FileDescriptor stderrFd = new FileDescriptor();
        writefd(stdinFd, inPipeWriteHandle.getValue());
        writefd(stdoutFd, outPipeReadHandle.getValue());
        writefd(stderrFd, errPipeReadHandle.getValue());
        stdin = new FileOutputStream(stdinFd);
        stdout = new FileInputStream(stdoutFd);
        stderr = new FileInputStream(stderrFd);
    }

    private void closeHandles() {
        Kernel32Util.closeHandles(outPipeWriteHandle.getValue(), outPipeReadHandle.getValue(),
                errPipeWriteHandle.getValue(), errPipeReadHandle.getValue(), inPipeReadHandle.getValue(),
                inPipeWriteHandle.getValue(), procInfo.hProcess, procInfo.hThread);
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    @Override
    public synchronized int waitFor() throws InterruptedException {
        if (procInfo == null) {
            throw new RuntimeException("process was not created");
        }
        if (WinBase.WAIT_FAILED == Kernel32.INSTANCE.WaitForSingleObject(procInfo.hProcess, Kernel32.INFINITE)) {
            throw getLastErrorAsException();
        }
        return exitValue();
    }

    @Override
    public synchronized int exitValue() {
        if (procInfo == null) {
            throw new RuntimeException("process was not created");
        }
        IntByReference exitCodeRef = new IntByReference();
        if (!Kernel32.INSTANCE.GetExitCodeProcess(procInfo.hProcess, exitCodeRef)) {
            throw getLastErrorAsException();
        }
        if (STILL_ACTIVE == exitCodeRef.getValue()) {
            throw new IllegalThreadStateException("process hasn't exited");
        }
        return exitCodeRef.getValue();
    }

    @Override
    public void destroy() {
        destroyForcibly();
    }

    @Override
    public synchronized Process destroyForcibly() {
        if (procInfo == null) {
            throw new RuntimeException("process was not created");
        }
        if (!Kernel32.INSTANCE.TerminateProcess(procInfo.hProcess, EXIT_CODE_TERMINATED)) {
            throw getLastErrorAsException();
        }
        return this;
    }

    public synchronized void setCurrentDirectory(String dir) {
        currentDirectory = dir;
    }

    private static LastErrorException getLastErrorAsException() {
        return new LastErrorException(Kernel32.INSTANCE.GetLastError());
    }

    private static void writefd(final FileDescriptor fd, final WinNT.HANDLE pointer) throws ProcessCreationException {
        // TODO this gets illegal reflective access warning on JDK11
        // switch to JNA Read/WriteFile and use Copier to bridge the streams
        final Field handleField;
        try {
            handleField = FileDescriptor.class.getDeclaredField("handle");
            handleField.setAccessible(true);
            handleField.setLong(fd, Pointer.nativeValue(pointer.getPointer()));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ProcessCreationException("cannot setup file descriptors", e);
        }
    }

    /**
     * Convert environment Map to lpEnvironment block format.
     * @return environment block for starting a process
     */
    private String computeEnvironmentBlock() {
        // TODO get the existing env vars for the user
        // Passing lpEnvironment to CreateProcessWithLogonW will overwrite the entire env
        // This is ugly but need to call "cmd /C set" first to get all user's existing env vars

        // Add SystemRoot env var if exists. See comment:
        // https://github.com/openjdk/jdk/blob/b17b821/src/java.base/windows/classes/java/lang/ProcessEnvironment.java#L309-L311
        String systemRootVal = System.getenv(SYSTEM_ROOT);
        if (systemRootVal != null) {
            additionalEnv.put(SYSTEM_ROOT, systemRootVal);
        }
        return Advapi32Util.getEnvironmentBlock(additionalEnv);
    }
}
