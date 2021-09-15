/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.exceptions.ProcessCreationException;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manage a Windows process running as a specific user.
 */
@SuppressFBWarnings({"IS2_INCONSISTENT_SYNC", "JLM_JSR166_UTILCONCURRENT_MONITORENTER"})
public class WindowsRunasProcess extends Process {
    private static final Logger logger = LogManager.getLogger(WindowsRunasProcess.class);
    private static final int PROCESS_CREATION_FLAGS = WinBase.CREATE_UNICODE_ENVIRONMENT  // use unicode
            | WinBase.CREATE_NO_WINDOW;  // don't create a window on desktop
    private static final char NULL_CHAR = '\0';
    public static final String SYSTEM_ROOT = "SystemRoot";
    public static final String SYSTEM_INTEGRITY_SID = "S-1-16-16384";
    public static final String SERVICE_GROUP_SID = "S-1-5-6";
    public static final int EXIT_CODE_TERMINATED = 130;

    private final String domain;
    private final String username;
    private final Map<String, String> additionalEnv = new HashMap<>();
    private String currentDirectory;

    private static final AtomicReference<WinNT.HANDLEByReference> processToken = new AtomicReference<>(null);
    private static final AtomicBoolean isService = new AtomicBoolean(true);

    @Getter
    private int pid = 0;
    private final AtomicReference<WinBase.PROCESS_INFORMATION> procInfo = new AtomicReference<>(null);
    private final AtomicBoolean exited = new AtomicBoolean(false);
    private final AtomicInteger exitCode = new AtomicInteger(-1);

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
     *
     * @param domain   name of domain that contains the user account
     * @param username name of the account
     */
    public WindowsRunasProcess(String domain, String username) {
        super();
        this.domain = domain;
        this.username = username;
    }

    /**
     * Set env vars in addition to the user's own existing env vars.
     *
     * @param envs a map of env vars to set
     */
    public void setEnv(Map<String, String> envs) {
        additionalEnv.putAll(envs);
    }

    public void setCurrentDirectory(String dir) {
        currentDirectory = dir;
    }

    /**
     * Starts the process.
     *
     * @param command program to execute with optional args
     * @throws IOException if failed to run subprocess
     */
    public synchronized void start(String... command) throws IOException {
        // Quote args if needed
        StringBuilder commandLine = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            String arg = command[i];
            // Space and \t require quoting otherwise won't be treated as a single arg
            if (!isQuoted(arg) && arg.matches(".*[ \t].*")) {
                commandLine.append('\"').append(arg).append('\"');
            } else {
                commandLine.append(arg);
            }
            if (i != command.length - 1) {
                commandLine.append(' ');
            }
        }

        // Get and cache process token and isService state
        synchronized (Advapi32.INSTANCE) {
            if (processToken.get() == null) {
                WinNT.HANDLEByReference processTokenHandle = new WinNT.HANDLEByReference();
                if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                        Kernel32.TOKEN_ALL_ACCESS, processTokenHandle)) {
                    throw lastErrorProcessCreationException("OpenProcessToken");
                }
                processToken.set(processTokenHandle);
                isService.set(isService(processTokenHandle));
                // If we are a service then we will need these privileges, so escalate now so we only do it once
                if (isService.get()) {
                    enablePrivileges(processTokenHandle.getValue(),
                            Kernel32.SE_TCB_NAME, Kernel32.SE_ASSIGNPRIMARYTOKEN_NAME);
                }
            }
        }

        // Start the process
        if (isService.get()) {
            processAsUser(commandLine.toString());
        } else {
            processWithLogon(commandLine.toString());
        }
    }

    private void processWithLogon(final String command) throws IOException {
        // TODO call cmd /c set and parse output
        char[] password = getPassword();
        // LogonUser
        boolean logonSuccess = false;
        WinNT.HANDLEByReference userTokenHandle = new WinNT.HANDLEByReference();
        int[] logonTypes =
                {Kernel32.LOGON32_LOGON_INTERACTIVE, Kernel32.LOGON32_LOGON_SERVICE, Kernel32.LOGON32_LOGON_BATCH};
        for (int logonType : logonTypes) {
            if (ProcAdvapi32.INSTANCE.LogonUser(username, domain, password, logonType,
                    Kernel32.LOGON32_PROVIDER_DEFAULT, userTokenHandle)) {
                logonSuccess = true;
                break;
            }
        }

        if (!logonSuccess) {
            throw lastErrorProcessCreationException("LogonUser");
        }

        // Load user profile
        final UserEnv.PROFILEINFO profileInfo = new UserEnv.PROFILEINFO();
        profileInfo.lpUserName = username;
        profileInfo.write();
        if (!UserEnv.INSTANCE.LoadUserProfile(userTokenHandle.getValue(), profileInfo)) {
            logger.warn("Unable to load user profile. Some environment variables may not be accessible",
                    lastErrorRuntimeException());
        }

        WinBase.STARTUPINFO startupInfo = initPipesAndStartupInfo();
        // TODO set additional env vars
        procInfo.set(new WinBase.PROCESS_INFORMATION());
        WinBase.PROCESS_INFORMATION procInfoLocal = procInfo.get();

        if (!ProcAdvapi32.INSTANCE.CreateProcessWithLogonW(username, domain, password, Advapi32.LOGON_WITH_PROFILE,
                null, command, PROCESS_CREATION_FLAGS, computeEnvironmentBlock(userTokenHandle.getValue()),
                currentDirectory, startupInfo, procInfoLocal)) {
            throw lastErrorProcessCreationException("CreateProcessWithLogonW");
        }
        Kernel32Util.closeHandle(procInfoLocal.hThread);
        Kernel32Util.closeHandleRefs(userTokenHandle);
        // Drop our reference to this end of the pipe since we just gave it away to the process
        Kernel32Util.closeHandles(startupInfo.hStdInput, startupInfo.hStdOutput, startupInfo.hStdError);
        Arrays.fill(password, NULL_CHAR);

        pid = procInfoLocal.dwProcessId.intValue();

        redirectStreams();
    }

    private void processAsUser(final String command)
            throws IOException {
        char[] password = getPassword();
        // LogonUser
        boolean logonSuccess = false;
        WinNT.HANDLEByReference userTokenHandle = new WinNT.HANDLEByReference();
        int[] logonTypes =
                {Kernel32.LOGON32_LOGON_INTERACTIVE, Kernel32.LOGON32_LOGON_SERVICE, Kernel32.LOGON32_LOGON_BATCH};
        for (int logonType : logonTypes) {
            if (ProcAdvapi32.INSTANCE.LogonUser(username, domain, password, logonType,
                    Kernel32.LOGON32_PROVIDER_DEFAULT, userTokenHandle)) {
                logonSuccess = true;
                break;
            }
        }

        // zero-out password buffer immediately after use
        Arrays.fill(password, NULL_CHAR);

        if (!logonSuccess) {
            throw lastErrorProcessCreationException("LogonUser");
        }

        // Init security descriptor
        // Constructor arg here cannot be empty/zero. Setting to 1 so that the internal structure is initialized.
        // API call will initialize it to proper size.
        final WinNT.SECURITY_DESCRIPTOR securityDescriptor = new WinNT.SECURITY_DESCRIPTOR(1);
        if (!Advapi32.INSTANCE.InitializeSecurityDescriptor(securityDescriptor, WinNT.SECURITY_DESCRIPTOR_REVISION)) {
            throw lastErrorProcessCreationException("InitializeSecurityDescriptor");
        }

        // NULL DACL is assigned to the security descriptor, which allows all access to the object
        if (!Advapi32.INSTANCE.SetSecurityDescriptorDacl(securityDescriptor, true, null, false)) {
            throw lastErrorProcessCreationException("SetSecurityDescriptorDacl");
        }

        // Duplicate userToken to create a "primary token" usable for CreateProcessAsUser
        final WinNT.HANDLEByReference primaryTokenHandle = new WinNT.HANDLEByReference();
        final WinBase.SECURITY_ATTRIBUTES processSecAttributes = new WinBase.SECURITY_ATTRIBUTES();
        processSecAttributes.lpSecurityDescriptor = securityDescriptor.getPointer();
        processSecAttributes.bInheritHandle = true;
        processSecAttributes.write();
        // DesiredAccess 0 to request the same access rights as the existing token
        if (!Advapi32.INSTANCE.DuplicateTokenEx(userTokenHandle.getValue(), 0, processSecAttributes,
                WinNT.SECURITY_IMPERSONATION_LEVEL.SecurityImpersonation, WinNT.TOKEN_TYPE.TokenPrimary,
                primaryTokenHandle)) {
            throw lastErrorProcessCreationException("DuplicateTokenEx");
        }

        // Load user profile
        final UserEnv.PROFILEINFO profileInfo = new UserEnv.PROFILEINFO();
        profileInfo.lpUserName = username;
        profileInfo.write();
        if (!UserEnv.INSTANCE.LoadUserProfile(primaryTokenHandle.getValue(), profileInfo)) {
            throw lastErrorProcessCreationException("LoadUserProfile");
        }

        // Create process
        final WinBase.SECURITY_ATTRIBUTES threadSecAttributes = new WinBase.SECURITY_ATTRIBUTES();
        threadSecAttributes.lpSecurityDescriptor = null;
        threadSecAttributes.bInheritHandle = false;
        threadSecAttributes.write();

        final WinBase.STARTUPINFO startupInfo = initPipesAndStartupInfo();
        procInfo.set(new WinBase.PROCESS_INFORMATION());
        WinBase.PROCESS_INFORMATION procInfoLocal = procInfo.get();
        if (!Advapi32.INSTANCE.CreateProcessAsUser(primaryTokenHandle.getValue(), null, command,
                // null application name. Just use the command
                processSecAttributes, threadSecAttributes, true,             // inherit handles true
                PROCESS_CREATION_FLAGS, computeEnvironmentBlock(primaryTokenHandle.getValue()), currentDirectory,
                startupInfo, procInfoLocal)) {
            throw lastErrorProcessCreationException("CreateProcessAsUser");
        }
        Kernel32Util.closeHandle(procInfoLocal.hThread);
        // Drop our reference to this end of the pipe since we just gave it away to the process
        Kernel32Util.closeHandles(startupInfo.hStdInput, startupInfo.hStdOutput, startupInfo.hStdError);

        pid = procInfoLocal.dwProcessId.intValue();

        redirectStreams();
        Kernel32Util.closeHandleRefs(userTokenHandle, primaryTokenHandle);
    }

    private char[] getPassword() throws IOException {
        byte[] credBlob = WindowsCredUtils.read(domain == null ? username : domain + "\\" + username);
        ByteBuffer bb = ByteBuffer.wrap(credBlob);
        CharBuffer cb = WindowsCredUtils.getCharsetForSystem().decode(bb);
        char[] password = new char[cb.length() + 1];
        cb.get(password, 0, cb.length());
        // char[] needs to be null terminated for windows
        password[password.length - 1] = NULL_CHAR;
        // zero-out temporary buffers
        Arrays.fill(cb.array(), (char) 0);
        Arrays.fill(bb.array(), (byte) 0);
        return password;
    }

    private static void enablePrivileges(WinNT.HANDLE processToken, String... privileges)
            throws ProcessCreationException {
        // Lookup privileges
        WinNT.TOKEN_PRIVILEGES tokenPrivileges = new WinNT.TOKEN_PRIVILEGES(privileges.length);
        for (int i = 0; i < privileges.length; i++) {
            WinNT.LUID luid = new WinNT.LUID();
            // First arg SystemName null to find the privilege name on the local system.
            if (!Advapi32.INSTANCE.LookupPrivilegeValue(null, privileges[i], luid)) {
                throw lastErrorProcessCreationException("LookupPrivilegeValue");
            }
            tokenPrivileges.Privileges[i] =
                    new WinNT.LUID_AND_ATTRIBUTES(luid, new WinDef.DWORD(Kernel32.SE_PRIVILEGE_ENABLED));
        }

        // Last 3 args are null because we don't care about the previous state
        if (!Advapi32.INSTANCE.AdjustTokenPrivileges(processToken, false, tokenPrivileges, 0, null, null)) {
            throw lastErrorProcessCreationException("AdjustTokenPrivileges");
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private WinBase.STARTUPINFO initPipesAndStartupInfo() throws IOException {
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
            throw lastErrorProcessCreationException("CreatePipe");
        }
        if (!Kernel32.INSTANCE.CreatePipe(outPipeReadHandle, outPipeWriteHandle, pipeSa, 0)) {
            throw lastErrorProcessCreationException("CreatePipe");
        }
        if (!Kernel32.INSTANCE.CreatePipe(errPipeReadHandle, errPipeWriteHandle, pipeSa, 0)) {
            throw lastErrorProcessCreationException("CreatePipe");
        }

        WinBase.STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
        startupInfo.dwFlags = WinBase.STARTF_USESTDHANDLES;
        startupInfo.hStdInput = inPipeReadHandle.getValue();
        startupInfo.hStdOutput = outPipeWriteHandle.getValue();
        startupInfo.hStdError = errPipeWriteHandle.getValue();
        startupInfo.write();
        return startupInfo;
    }

    @SuppressWarnings("PMD.AvoidFileStream")
    private void redirectStreams() throws ProcessCreationException {
        FileDescriptor stdinFd = new FileDescriptor();
        FileDescriptor stdoutFd = new FileDescriptor();
        FileDescriptor stderrFd = new FileDescriptor();
        writefd(stdinFd, inPipeWriteHandle.getValue());
        writefd(stdoutFd, outPipeReadHandle.getValue());
        writefd(stderrFd, errPipeReadHandle.getValue());
        stdin = new FileOutputStream(stdinFd);
        stdout = new BufferedInputStream(new FileInputStream(stdoutFd));
        stderr = new BufferedInputStream(new FileInputStream(stderrFd));
    }

    private static void closeSafely(final WinNT.HANDLEByReference ref) {
        final WinNT.HANDLE handle = ref.getValue();
        if (handle != null) {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    @SuppressFBWarnings("DE_MIGHT_IGNORE")
    @SuppressWarnings({"PMD.NullAssignment", "PMD.AvoidCatchingGenericException"})
    private synchronized void closeHandles() {
        if (outPipeReadHandle != null) {
            try {
                stdin.close();
            } catch (Exception ignore) {
            }
            closeSafely(inPipeWriteHandle);
            try {
                stdout.close();
            } catch (Exception ignore) {
            }
            closeSafely(outPipeReadHandle);
            try {
                stderr.close();
            } catch (Exception ignore) {
            }
            closeSafely(errPipeReadHandle);
            outPipeWriteHandle = null;
            outPipeReadHandle = null;
            errPipeWriteHandle = null;
            errPipeReadHandle = null;
            inPipeReadHandle = null;
            inPipeWriteHandle = null;
        }
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

    @SuppressWarnings({"checkstyle:NoFinalizer", "PMD.NullAssignment"})
    @Override
    protected void finalize() throws Throwable {
        WinBase.PROCESS_INFORMATION localProcInfo = procInfo.get();
        if (localProcInfo != null && localProcInfo.hProcess != null) {
            Kernel32.INSTANCE.CloseHandle(localProcInfo.hProcess);
            localProcInfo.hProcess = null;
        }
        super.finalize();
    }

    @Override
    public int waitFor() throws InterruptedException {
        if (procInfo.get() == null) {
            throw new RuntimeException("process was not created");
        }
        while (isAlive()) {
            Thread.sleep(250);
        }
        return exitValue();
    }

    @Override
    public int exitValue() {
        if (procInfo.get() == null) {
            throw new RuntimeException("process was not created");
        }
        if (isAlive()) {
            throw new IllegalThreadStateException("process hasn't exited");
        }
        return exitCode.get();
    }

    @Override
    public synchronized boolean isAlive() {
        synchronized (exited) {
            if (exited.get()) {
                return false;
            }
            IntByReference exitCodeRef = new IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(procInfo.get().hProcess, exitCodeRef)) {
                logger.info("GetExitCodeProcess failed {} {} {}", procInfo.get().hProcess,
                        exited.get(), exitCode.get());
                throw lastErrorRuntimeException();
            }
            if (WinBase.STILL_ACTIVE == exitCodeRef.getValue()) {
                return true;
            }
            exitCode.set(exitCodeRef.getValue());
            exited.set(true);
            return false;
        }
    }

    @Override
    public void destroy() {
        destroyForcibly();
    }

    @Override
    public synchronized Process destroyForcibly() {
        synchronized (exited) {
            WinBase.PROCESS_INFORMATION localProcInfo = procInfo.get();
            if (localProcInfo == null || localProcInfo.hProcess == null || exited.get()) {
                closeHandles();
                return this;
            }
            if (!Kernel32.INSTANCE.TerminateProcess(localProcInfo.hProcess, EXIT_CODE_TERMINATED)) {
                logger.warn("Terminate process failed {}", Kernel32Util.getLastErrorMessage());
            }
            exitCode.set(EXIT_CODE_TERMINATED);
            exited.set(true);
        }
        closeHandles();
        return this;
    }

    private static LastErrorException lastErrorRuntimeException() {
        return new LastErrorException(Kernel32.INSTANCE.GetLastError());
    }

    private static ProcessCreationException lastErrorProcessCreationException(String context) {
        return new ProcessCreationException(String.format("[%s] %s", context, Kernel32Util.getLastErrorMessage()));
    }

    private static boolean isService(WinNT.HANDLEByReference processToken) throws ProcessCreationException {
        // Get required buffer size
        IntByReference returnLength = new IntByReference();
        if (!Advapi32.INSTANCE.GetTokenInformation(processToken.getValue(), WinNT.TOKEN_INFORMATION_CLASS.TokenGroups,
                null, 0, returnLength)) {
            int lastErrorCode = Kernel32.INSTANCE.GetLastError();
            if (lastErrorCode != WinError.ERROR_INSUFFICIENT_BUFFER) {
                throw new ProcessCreationException(String.format("[%s] %s", "GetTokenInformation",
                        Kernel32Util.formatMessageFromLastErrorCode(lastErrorCode)));
            }
        }
        WinNT.TOKEN_GROUPS tokenGroups = new WinNT.TOKEN_GROUPS(returnLength.getValue());
        // Get actual info
        if (!Advapi32.INSTANCE.GetTokenInformation(processToken.getValue(), WinNT.TOKEN_INFORMATION_CLASS.TokenGroups,
                tokenGroups, returnLength.getValue(), returnLength)) {
            throw lastErrorProcessCreationException("GetTokenInformation");
        }

        WinNT.SID_AND_ATTRIBUTES[] groups = tokenGroups.getGroups();
        if (logger.isTraceEnabled()) {
            Arrays.stream(groups).forEach(g -> logger.atTrace().kv("token group", g.Sid.getSidString()).log());
        }
        return Arrays.stream(groups).map(g -> g.Sid.getSidString())
                .anyMatch(g -> g.equalsIgnoreCase(SERVICE_GROUP_SID) || g.equalsIgnoreCase(SYSTEM_INTEGRITY_SID));
    }

    private static void writefd(final FileDescriptor fd, final WinNT.HANDLE pointer) throws ProcessCreationException {
        // TODO this gets illegal reflective access warning on newer JDK
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
     *
     * @return environment block for starting a process
     * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-createenvironmentblock">docs</a>
     */
    private String computeEnvironmentBlock(WinNT.HANDLE userTokenHandle) throws ProcessCreationException {
        PointerByReference lpEnv = new PointerByReference();
        // Get user's env vars, inheriting current process env
        if (!UserEnv.INSTANCE.CreateEnvironmentBlock(lpEnv, userTokenHandle, true)) {
            throw lastErrorProcessCreationException("CreateEnvironmentBlock");
        }

        // The above API returns pointer to a block of null-terminated strings. It ends with two nulls (\0\0).
        Map<String, String> userEnvMap = new HashMap<>();
        int offset = 0;
        while (true) {
            String s = lpEnv.getValue().getWideString(offset);
            if (s.length() == 0) {
                break;
            }
            // wide string uses 2 bytes per char. +2 to skip the terminating null
            offset += s.length() * 2 + 2;
            int splitInd = s.indexOf('=');
            userEnvMap.put(s.substring(0, splitInd), s.substring(splitInd + 1));
        }

        if (!UserEnv.INSTANCE.DestroyEnvironmentBlock(lpEnv.getValue())) {
            throw lastErrorProcessCreationException("DestroyEnvironmentBlock");
        }

        // Set additional envs on top of user default env
        userEnvMap.putAll(additionalEnv);

        return Advapi32Util.getEnvironmentBlock(userEnvMap);
    }

    private static boolean isQuoted(String s) {
        return s.startsWith("\"") && s.endsWith("\"") && !s.endsWith("\\\"");
    }
}
