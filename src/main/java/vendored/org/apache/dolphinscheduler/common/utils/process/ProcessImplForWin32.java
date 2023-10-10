/*
 * Original license from the dolphinscheduler project:
 * https://github.com/apache/dolphinscheduler/tree/381d23e
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications by Amazon: (see code comments for Amazon modifications)
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package vendored.org.apache.dolphinscheduler.common.utils.process;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.exceptions.ProcessCreationException;
import com.aws.greengrass.util.platforms.windows.UserEnv;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.Wincon;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import lombok.Getter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sun.jna.Native.POINTER_SIZE;
import static com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE;
import static com.sun.jna.platform.win32.WinBase.STILL_ACTIVE;
import static java.util.Objects.requireNonNull;

public class ProcessImplForWin32 extends Process {

    private static final Field FD_HANDLE;

    static {
        try {
            FD_HANDLE = requireNonNull(FileDescriptor.class.getDeclaredField("handle"));
            FD_HANDLE.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final int PIPE_SIZE = 4096 + 24;

    private static final int HANDLE_STORAGE_SIZE = 6;

    private static final int OFFSET_READ = 0;

    private static final int OFFSET_WRITE = 1;

    // From https://github.com/java-native-access/jna/blob/cb98ab22196855933eb6315f2663d1b4a03ff261/contrib/platform/src/com/sun/jna/platform/win32/WinBase.java#L51-L53
    // 4294967295 is 2^32.
    // Pointer size 8 bytes means 64 bit. 4 bytes would mean 32 bit.
    private static final WinNT.HANDLE JAVA_INVALID_HANDLE_VALUE =
            new WinNT.HANDLE(Pointer.createConstant(POINTER_SIZE == 8 ? -1L : 4294967295L));

    /*
     * Begin Amazon addition.
     */

    public static final int EXIT_CODE_TERMINATED = 130;
    private static final String SYSTEM_INTEGRITY_SID = "S-1-16-16384";
    private static final String SERVICE_GROUP_SID = "S-1-5-6";

    private static final AtomicReference<WinNT.HANDLEByReference> processToken = new AtomicReference<>(null);
    private static final AtomicBoolean isService = new AtomicBoolean(true);
    private static final Logger logger = LogManager.getLogger(ProcessImplForWin32.class);

    @Getter
    private int pid = 0;

    /*
     * End Amazon addition.
     */

    private static void setHandle(FileDescriptor obj, long handle) {
        try {
            FD_HANDLE.set(obj, handle);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static long getHandle(FileDescriptor obj) {
        try {
            return (Long) FD_HANDLE.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Open a file for writing. If {@code append} is {@code true} then the file
     * is opened for atomic append directly and a FileOutputStream constructed
     * with the resulting handle. This is because a FileOutputStream created
     * to append to a file does not open the file in a manner that guarantees
     * that writes by the child process will be atomic.
     */
    private static FileOutputStream newFileOutputStream(File f, boolean append)
            throws IOException
    {
        if (append) {
            String path = f.getPath();
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                sm.checkWrite(path);
            }
            long handle = openForAtomicAppend(path);
            final FileDescriptor fd = new FileDescriptor();
            setHandle(fd, handle);
            return AccessController.doPrivileged(
                    new PrivilegedAction<FileOutputStream>() {
                        @Override
                        public FileOutputStream run() {
                            return new FileOutputStream(fd);
                        }
                    }
            );
        } else {
            return new FileOutputStream(f);
        }
    }

    // System-dependent portion of ProcessBuilderForWindows.start()
    static Process start(String username,
                         char[] password,
                         String[] cmdarray,
                         java.util.Map<String,String> defaultEnv,
                         java.util.Map<String,String> overrideEnv,
                         String dir,
                         ProcessBuilderForWin32.Redirect[] redirects,
                         boolean redirectErrorStream,
                         int processCreationFlags)
            throws IOException
    {
        FileInputStream  f0 = null;
        FileOutputStream f1 = null;
        FileOutputStream f2 = null;

        try {
            long[] stdHandles;
            if (redirects == null) {
                stdHandles = new long[] { -1L, -1L, -1L };
            } else {
                stdHandles = new long[3];

                if (redirects[0] == ProcessBuilderForWin32.Redirect.PIPE) {
                    stdHandles[0] = -1L;
                } else if (redirects[0] == ProcessBuilderForWin32.Redirect.INHERIT) {
                    stdHandles[0] = getHandle(FileDescriptor.in);
                } else {
                    f0 = new FileInputStream(redirects[0].file());
                    stdHandles[0] = getHandle(f0.getFD());
                }

                if (redirects[1] == ProcessBuilderForWin32.Redirect.PIPE) {
                    stdHandles[1] = -1L;
                } else if (redirects[1] == ProcessBuilderForWin32.Redirect.INHERIT) {
                    stdHandles[1] = getHandle(FileDescriptor.out);
                } else {
                    f1 = newFileOutputStream(redirects[1].file(),
                            redirects[1].append());
                    stdHandles[1] = getHandle(f1.getFD());
                }

                if (redirects[2] == ProcessBuilderForWin32.Redirect.PIPE) {
                    stdHandles[2] = -1L;
                } else if (redirects[2] == ProcessBuilderForWin32.Redirect.INHERIT) {
                    stdHandles[2] = getHandle(FileDescriptor.err);
                } else {
                    f2 = newFileOutputStream(redirects[2].file(),
                            redirects[2].append());
                    stdHandles[2] = getHandle(f2.getFD());
                }
            }

            return new ProcessImplForWin32(username, password, cmdarray, defaultEnv, overrideEnv, dir, stdHandles,
                    redirectErrorStream, processCreationFlags);
        } finally {
            // In theory, close() can throw IOException
            // (although it is rather unlikely to happen here)
            try { if (f0 != null) {
                f0.close();
            }
            }
            finally {
                try { if (f1 != null) {
                    f1.close();
                }
                }
                finally { if (f2 != null) {
                    f2.close();
                }
                }
            }
        }

    }

    private static class LazyPattern {
        // Escape-support version:
        //    "(\")((?:\\\\\\1|.)+?)\\1|([^\\s\"]+)"
        private static final Pattern PATTERN =
                Pattern.compile("[^\\s\"]+|\"[^\"]*\"");
    }

    /* Parses the command string parameter into the executable name and
     * program arguments.
     *
     * The command string is broken into tokens. The token separator is a space
     * or quota character. The space inside quotation is not a token separator.
     * There are no escape sequences.
     */
    private static String[] getTokensFromCommand(String command) {
        ArrayList<String> matchList = new ArrayList<>(8);
        Matcher regexMatcher = ProcessImplForWin32.LazyPattern.PATTERN.matcher(command);
        while (regexMatcher.find()) {
            matchList.add(regexMatcher.group());
        }
        return matchList.toArray(new String[matchList.size()]);
    }

    private static final int VERIFICATION_CMD_BAT = 0;
    private static final int VERIFICATION_WIN32 = 1;
    private static final int VERIFICATION_WIN32_SAFE = 2; // inside quotes not allowed
    private static final int VERIFICATION_LEGACY = 3;
    // See Command shell overview for documentation of special characters.
    // https://docs.microsoft.com/en-us/previous-versions/windows/it-pro/windows-xp/bb490954(v=technet.10)
    private static final char[][] ESCAPE_VERIFICATION = {
            // We guarantee the only command file execution for implicit [cmd.exe] run.
            //    http://technet.microsoft.com/en-us/library/bb490954.aspx
            {' ', '\t', '<', '>', '&', '|', '^'},
            {' ', '\t', '<', '>'},
            {' ', '\t', '<', '>'},
            {' ', '\t'}
    };

    private static String createCommandLine(int verificationType,
                                            final String executablePath,
                                            final String[] cmd)
    {
        StringBuilder cmdbuf = new StringBuilder(80);

        cmdbuf.append(executablePath);

        for (int i = 1; i < cmd.length; ++i) {
            cmdbuf.append(' ');
            String s = cmd[i];
            if (needsEscaping(verificationType, s)) {
                cmdbuf.append('"');

                if (verificationType == VERIFICATION_WIN32_SAFE) {
                    // Insert the argument, adding '\' to quote any interior quotes
                    int length = s.length();
                    for (int j = 0; j < length; j++) {
                        char c = s.charAt(j);
                        if (c == DOUBLEQUOTE) {
                            int count = countLeadingBackslash(verificationType, s, j);
                            while (count-- > 0) {
                                cmdbuf.append(BACKSLASH);   // double the number of backslashes
                            }
                            cmdbuf.append(BACKSLASH);       // backslash to quote the quote
                        }
                        cmdbuf.append(c);
                    }
                } else {
                    cmdbuf.append(s);
                }
                // The code protects the [java.exe] and console command line
                // parser, that interprets the [\"] combination as an escape
                // sequence for the ["] char.
                //     http://msdn.microsoft.com/en-us/library/17w5ykft.aspx
                //
                // If the argument is an FS path, doubling of the tail [\]
                // char is not a problem for non-console applications.
                //
                // The [\"] sequence is not an escape sequence for the [cmd.exe]
                // command line parser. The case of the [""] tail escape
                // sequence could not be realized due to the argument validation
                // procedure.
                int count = countLeadingBackslash(verificationType, s, s.length());
                while (count-- > 0) {
                    cmdbuf.append(BACKSLASH);   // double the number of backslashes
                }
                cmdbuf.append('"');
            } else {
                cmdbuf.append(s);
            }
        }
        return cmdbuf.toString();
    }

    /**
     * Return the argument without quotes (1st and last) if present, else the arg.
     * @param str a string
     * @return the string without 1st and last quotes
     */
    private static String unQuote(String str) {
        int len = str.length();
        return (len >= 2 && str.charAt(0) == DOUBLEQUOTE && str.charAt(len - 1) == DOUBLEQUOTE)
                ? str.substring(1, len - 1)
                : str;
    }

    private static boolean needsEscaping(int verificationType, String arg) {
        // Switch off MS heuristic for internal ["].
        // Please, use the explicit [cmd.exe] call
        // if you need the internal ["].
        //    Example: "cmd.exe", "/C", "Extended_MS_Syntax"

        // For [.exe] or [.com] file the unpaired/internal ["]
        // in the argument is not a problem.
        String unquotedArg = unQuote(arg);
        boolean argIsQuoted = !arg.equals(unquotedArg);
        boolean embeddedQuote = unquotedArg.indexOf(DOUBLEQUOTE) >= 0;

        switch (verificationType) {
            case VERIFICATION_CMD_BAT:
                if (embeddedQuote) {
                    throw new IllegalArgumentException("Argument has embedded quote, " +
                            "use the explicit CMD.EXE call.");
                }
                break;  // break determine whether to quote
            case VERIFICATION_WIN32_SAFE:
                if (argIsQuoted && embeddedQuote)  {
                    throw new IllegalArgumentException("Malformed argument has embedded quote: "
                            + unquotedArg);
                }
                break;
            default:
                break;
        }

        if (!argIsQuoted) {
            char[] testEscape = ESCAPE_VERIFICATION[verificationType];
            for (int i = 0; i < testEscape.length; ++i) {
                if (arg.indexOf(testEscape[i]) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String getExecutablePath(String path)
            throws IOException
    {
        String name = unQuote(path);
        if (name.indexOf(DOUBLEQUOTE) >= 0) {
            throw new IllegalArgumentException("Executable name has embedded quote, " +
                    "split the arguments: " + name);
        }
        // Win32 CreateProcess requires path to be normalized
        File fileToRun = new File(name);

        // From the [CreateProcess] function documentation:
        //
        // "If the file name does not contain an extension, .exe is appended.
        // Therefore, if the file name extension is .com, this parameter
        // must include the .com extension. If the file name ends in
        // a period (.) with no extension, or if the file name contains a path,
        // .exe is not appended."
        //
        // "If the file name !does not contain a directory path!,
        // the system searches for the executable file in the following
        // sequence:..."
        //
        // In practice ANY non-existent path is extended by [.exe] extension
        // in the [CreateProcess] function with the only exception:
        // the path ends by (.)

        return fileToRun.getPath();
    }

    /**
     * An executable is any program that is an EXE or does not have an extension
     * and the Windows createProcess will be looking for .exe.
     * The comparison is case insensitive based on the name.
     * @param executablePath the executable file
     * @return true if the path ends in .exe or does not have an extension.
     */
    private boolean isExe(String executablePath) {
        File file = new File(executablePath);
        String upName = file.getName().toUpperCase(Locale.ROOT);
        return (upName.endsWith(".EXE") || upName.indexOf('.') < 0);
    }

    // Old version that can be bypassed
    private boolean isShellFile(String executablePath) {
        String upPath = executablePath.toUpperCase();
        return (upPath.endsWith(".CMD") || upPath.endsWith(".BAT"));
    }

    private String quoteString(String arg) {
        StringBuilder argbuf = new StringBuilder(arg.length() + 2);
        return argbuf.append('"').append(arg).append('"').toString();
    }

    // Count backslashes before start index of string.
    // .bat files don't include backslashes as part of the quote
    private static int countLeadingBackslash(int verificationType,
                                             CharSequence input, int start) {
        if (verificationType == VERIFICATION_CMD_BAT) {
            return 0;
        }
        int j;
        for (j = start - 1; j >= 0 && input.charAt(j) == BACKSLASH; j--) {
            // just scanning backwards
        }
        return (start - 1) - j;  // number of BACKSLASHES
    }

    private static final char DOUBLEQUOTE = '\"';
    private static final char BACKSLASH = '\\';

    private WinNT.HANDLE handle;
    private OutputStream stdinStream;
    private InputStream stdoutStream;
    private InputStream stderrStream;

    private ProcessImplForWin32(
            String username,
            char[] password,
            String[] cmd,
            final java.util.Map<String,String> defaultEnv,
            final java.util.Map<String,String> overrideEnv,
            final String path,
            final long[] stdHandles,
            final boolean redirectErrorStream,
            final int processCreationFlags)
            throws IOException
    {
        String cmdstr;
        final SecurityManager security = System.getSecurityManager();
        if (security == null) {
            // Legacy mode.

            // Normalize path if possible.
            String executablePath = new File(cmd[0]).getPath();

            // No worry about internal, unpaired ["], and redirection/piping.
            if (needsEscaping(VERIFICATION_LEGACY, executablePath) ) {
                executablePath = quoteString(executablePath);
            }

            cmdstr = createCommandLine(
                    //legacy mode doesn't worry about extended verification
                    VERIFICATION_LEGACY,
                    executablePath,
                    cmd);
        } else {
            String executablePath;
            try {
                executablePath = getExecutablePath(cmd[0]);
            } catch (IllegalArgumentException e) {
                // Workaround for the calls like
                // Runtime.getRuntime().exec("\"C:\\Program Files\\foo\" bar")

                // No chance to avoid CMD/BAT injection, except to do the work
                // right from the beginning. Otherwise we have too many corner
                // cases from
                //    Runtime.getRuntime().exec(String[] cmd [, ...])
                // calls with internal ["] and escape sequences.

                // Restore original command line.
                StringBuilder join = new StringBuilder();
                // terminal space in command line is ok
                for (String s : cmd) {
                    join.append(s).append(' ');
                }

                // Parse the command line again.
                cmd = getTokensFromCommand(join.toString());
                executablePath = getExecutablePath(cmd[0]);

                // Check new executable name once more
                if (security != null) {
                    security.checkExec(executablePath);
                }
            }

            // Quotation protects from interpretation of the [path] argument as
            // start of longer path with spaces. Quotation has no influence to
            // [.exe] extension heuristic.
            boolean isShell = isShellFile(executablePath);
            cmdstr = createCommandLine(
                    // We need the extended verification procedures
                    isShell ? VERIFICATION_CMD_BAT : VERIFICATION_WIN32,
                    quoteString(executablePath),
                    cmd);
        }

        handle = create(username, password, cmdstr, defaultEnv, overrideEnv, path, stdHandles, redirectErrorStream,
                processCreationFlags);

        AccessController.doPrivileged(
                new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        if (stdHandles[0] == -1L) {
                            stdinStream = ProcessBuilderForWin32.NullOutputStream.INSTANCE;
                        } else {
                            FileDescriptor stdinFd = new FileDescriptor();
                            setHandle(stdinFd, stdHandles[0]);
                            stdinStream = new BufferedOutputStream(
                                    new FileOutputStream(stdinFd));
                        }

                        if (stdHandles[1] == -1L) {
                            stdoutStream = ProcessBuilderForWin32.NullInputStream.INSTANCE;
                        } else {
                            FileDescriptor stdoutFd = new FileDescriptor();
                            setHandle(stdoutFd, stdHandles[1]);
                            stdoutStream = new BufferedInputStream(
                                    new FileInputStream(stdoutFd));
                        }

                        if (stdHandles[2] == -1L) {
                            stderrStream = ProcessBuilderForWin32.NullInputStream.INSTANCE;
                        } else {
                            FileDescriptor stderrFd = new FileDescriptor();
                            setHandle(stderrFd, stdHandles[2]);
                            stderrStream = new FileInputStream(stderrFd);
                        }

                        return null; }});
    }

    @Override
    public OutputStream getOutputStream() {
        return stdinStream;
    }

    @Override
    public InputStream getInputStream() {
        return stdoutStream;
    }

    @Override
    public InputStream getErrorStream() {
        return stderrStream;
    }

    @Override
    protected void finalize() {
        closeHandle(handle);
    }

    @Override
    public int exitValue() {
        int exitCode = getExitCodeProcess(handle);
        if (exitCode == STILL_ACTIVE) {
            throw new IllegalThreadStateException("process has not exited");
        }
        return exitCode;
    }

    @Override
    public int waitFor() throws InterruptedException {
        // Poll waitFor with a 1 second timeout. If we do not do this, this thread cannot be interrupted
        while (!waitFor(1, TimeUnit.SECONDS)) {
        }
        return exitValue();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit)
            throws InterruptedException
    {
        if (getExitCodeProcess(handle) != STILL_ACTIVE) {
            return true;
        }
        if (timeout <= 0) {
            return false;
        }

        long remainingNanos  = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos ;

        do {
            // Round up to next millisecond
            long msTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNanos + 999_999L);
            waitForTimeoutInterruptibly(handle, msTimeout);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (getExitCodeProcess(handle) != STILL_ACTIVE) {
                return true;
            }
            remainingNanos = deadline - System.nanoTime();
        } while (remainingNanos > 0);

        return (getExitCodeProcess(handle) != STILL_ACTIVE);
    }

    @Override
    public void destroy() { terminateProcess(handle); }

    @Override
    public Process destroyForcibly() {
        destroy();
        return this;
    }
    @Override
    public boolean isAlive() {
        return isProcessAlive(handle);
    }

    private static boolean initHolder(WinNT.HANDLEByReference pjhandles,
                                      WinNT.HANDLEByReference[] pipe,
                                      int offset,
                                      WinNT.HANDLEByReference phStd) {
        if (!pjhandles.getValue().equals(JAVA_INVALID_HANDLE_VALUE)) {
            phStd.setValue(pjhandles.getValue());
            pjhandles.setValue(JAVA_INVALID_HANDLE_VALUE);
        } else {
            if (!Kernel32.INSTANCE.CreatePipe(pipe[0], pipe[1], null, PIPE_SIZE)) {
                throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
            } else {
                WinNT.HANDLE thisProcessEnd = offset == OFFSET_READ ? pipe[1].getValue() : pipe[0].getValue();
                phStd.setValue(pipe[offset].getValue());
                pjhandles.setValue(thisProcessEnd);
            }
        }
        Kernel32.INSTANCE.SetHandleInformation(phStd.getValue(), WinBase.HANDLE_FLAG_INHERIT, WinBase.HANDLE_FLAG_INHERIT);
        return true;
    }

    private static void releaseHolder(boolean complete, WinNT.HANDLEByReference[] pipe, int offset) {
        closeHandle(pipe[offset].getValue());
        if (complete) {
            closeHandle(pipe[offset == OFFSET_READ ? OFFSET_WRITE : OFFSET_READ].getValue());
        }
    }

    private static void prepareIOEHandleState(WinNT.HANDLE[] stdIOE, Boolean[] inherit) {
        for(int i = 0; i < HANDLE_STORAGE_SIZE; ++i) {
            WinNT.HANDLE hstd = stdIOE[i];
            if (!WinBase.INVALID_HANDLE_VALUE.equals(hstd)) {
                inherit[i] = Boolean.TRUE;
                Kernel32.INSTANCE.SetHandleInformation(hstd, WinBase.HANDLE_FLAG_INHERIT, 0);
            }
        }
    }

    private static void restoreIOEHandleState(WinNT.HANDLE[] stdIOE, Boolean[] inherit) {
        for (int i = HANDLE_STORAGE_SIZE - 1; i >= 0; --i) {
            if (!WinBase.INVALID_HANDLE_VALUE.equals(stdIOE[i])) {
                Kernel32.INSTANCE.SetHandleInformation(stdIOE[i], WinBase.HANDLE_FLAG_INHERIT, Boolean.TRUE.equals(inherit[i]) ? WinBase.HANDLE_FLAG_INHERIT : 0);
            }
        }
    }

    /*
     * Method modified by Amazon to be able to call CreateProcessAsUser when running as a Windows service
     */
    private WinNT.HANDLE processCreate(String username,
                                       char[] password,
                                       String cmd,
                                       final String envblock,
                                       final String path,
                                       final WinNT.HANDLEByReference[] stdHandles,
                                       final boolean redirectErrorStream,
                                       final ProcessCreationExtras extraInfo,
                                       final int processCreationFlags) throws ProcessCreationException {
        WinNT.HANDLE ret = new WinNT.HANDLE(Pointer.createConstant(0));

        WinNT.HANDLE[] stdIOE = new WinNT.HANDLE[] {
                WinBase.INVALID_HANDLE_VALUE, WinBase.INVALID_HANDLE_VALUE, WinBase.INVALID_HANDLE_VALUE,
                stdHandles[0].getValue(), stdHandles[1].getValue(), stdHandles[2].getValue()
        };
        stdIOE[0] = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_INPUT_HANDLE);
        stdIOE[1] = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_OUTPUT_HANDLE);
        stdIOE[2] = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_ERROR_HANDLE);

        Boolean[] inherit = new Boolean[] {
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE
        };

        prepareIOEHandleState(stdIOE, inherit);

        // input
        WinNT.HANDLEByReference hStdInput = new WinNT.HANDLEByReference();
        WinNT.HANDLEByReference[] pipeIn = new WinNT.HANDLEByReference[] {
                new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE), new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE) };

        // output
        WinNT.HANDLEByReference hStdOutput = new WinNT.HANDLEByReference();
        WinNT.HANDLEByReference[] pipeOut = new WinNT.HANDLEByReference[] {
                new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE), new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE) };

        // error
        WinNT.HANDLEByReference hStdError = new WinNT.HANDLEByReference();
        WinNT.HANDLEByReference[] pipeError = new WinNT.HANDLEByReference[] {
                new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE), new WinNT.HANDLEByReference(WinBase.INVALID_HANDLE_VALUE) };

        boolean ioRedirectSuccess;
        if (initHolder(stdHandles[0], pipeIn, OFFSET_READ, hStdInput)) {
            if (initHolder(stdHandles[1], pipeOut, OFFSET_WRITE, hStdOutput)) {
                WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
                si.hStdInput = hStdInput.getValue();
                si.hStdOutput = hStdOutput.getValue();

                if (redirectErrorStream) {
                    si.hStdError = si.hStdOutput;
                    stdHandles[2].setValue(JAVA_INVALID_HANDLE_VALUE);
                    ioRedirectSuccess = true;
                } else {
                    ioRedirectSuccess = initHolder(stdHandles[2], pipeError, OFFSET_WRITE, hStdError);
                    si.hStdError = hStdError.getValue();
                }

                if (ioRedirectSuccess) {
                    WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
                    pi.clear();
                    si.dwFlags = WinBase.STARTF_USESTDHANDLES | WinBase.STARTF_USESHOWWINDOW;
                    si.wShowWindow = new WinDef.WORD(WinUser.SW_HIDE);  // hide new console window
                    si.write();
                    final boolean createProcSuccess;
                    final String createProcContext;
                    final int createProcError;

                    if (username == null) {
                        createProcContext = "CreateProcess as current user";

                        // From API doc:
                        // The Unicode version of this function, CreateProcessW, can modify the contents of this string
                        // Therefore, this parameter cannot be a pointer to read-only memory
                        // (such as a const variable or a literal string). If this parameter is a constant string,
                        // the function may cause an access violation.
                        WTypes.LPWSTR cmdWstr = new WTypes.LPWSTR(cmd);
                        char[] cmdChars = cmdWstr.getPointer()
                                .getCharArray(0, cmd.length() + 1);  // +1 terminating null char
                        WTypes.LPWSTR lpEnvironment = envblock == null ? new WTypes.LPWSTR() : new WTypes.LPWSTR(envblock);
                        createProcSuccess = Kernel32.INSTANCE.CreateProcessW(null, cmdChars, null, null,
                                true, new WinDef.DWORD(processCreationFlags), lpEnvironment.getPointer(), path, si, pi);
                        createProcError = Kernel32.INSTANCE.GetLastError();
                    } else if (isService.get()) {
                        createProcContext = "CreateProcessAsUser";

                        final WinBase.SECURITY_ATTRIBUTES threadSa = new WinBase.SECURITY_ATTRIBUTES();
                        threadSa.lpSecurityDescriptor = null;
                        threadSa.bInheritHandle = false;
                        threadSa.write();

                        createProcSuccess = Advapi32.INSTANCE.CreateProcessAsUser(extraInfo.primaryTokenHandle, null, cmd,
                                extraInfo.processSa, threadSa, true, processCreationFlags,
                                envblock, path, si, pi);
                        // track error since closeHandles will reset it
                        createProcError = Kernel32.INSTANCE.GetLastError();
                        Kernel32Util.closeHandles(extraInfo.primaryTokenHandle);
                    } else {
                        createProcContext = "CreateProcessWithLogonW";

                        WTypes.LPWSTR lpEnvironment = envblock == null ? new WTypes.LPWSTR() : new WTypes.LPWSTR(envblock);
                        createProcSuccess =
                                Advapi32.INSTANCE.CreateProcessWithLogonW(username, null, new String(password),
                                        Advapi32.LOGON_WITH_PROFILE, null, cmd, processCreationFlags,
                                        lpEnvironment.getPointer(), path, si, pi);
                        createProcError = Kernel32.INSTANCE.GetLastError();
                    }

                    if (createProcSuccess) {
                        closeHandle(pi.hThread);
                        ret = pi.hProcess;
                        pid = pi.dwProcessId.intValue();
                    } else {
                        throw lastErrorProcessCreationException(createProcContext, createProcError);
                    }
                }
                releaseHolder(ret.getPointer().equals(Pointer.createConstant(0)), pipeError, OFFSET_WRITE);
                releaseHolder(ret.getPointer().equals(Pointer.createConstant(0)), pipeOut, OFFSET_WRITE);
            }
            releaseHolder(ret.getPointer().equals(Pointer.createConstant(0)), pipeIn, OFFSET_READ);
        }
        restoreIOEHandleState(stdIOE, inherit);
        return ret;
    }

    /*
     * Method modified by Amazon to be able to call CreateProcessAsUser when running as a Windows service
     */
    private synchronized WinNT.HANDLE create(String username,
                                             char[] password,
                                             String cmd,
                                             java.util.Map<String,String> defaultEnv,
                                             java.util.Map<String,String> overrideEnv,
                                             final String path,
                                             final long[] stdHandles,
                                             final boolean redirectErrorStream,
                                             final int processCreationFlags) throws ProcessCreationException {
        String envblock;
        ProcessCreationExtras extraInfo = new ProcessCreationExtras();
        if (defaultEnv == null) {
            defaultEnv = Collections.emptyMap();
        }
        if (overrideEnv == null) {
            overrideEnv = Collections.emptyMap();
        }
        if (username == null) {
            // Windows env var keys are case-insensitive. Use case-insensitive map to avoid collision
            Map<String, String> mergedEnv = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            mergedEnv.putAll(defaultEnv);
            mergedEnv.putAll(overrideEnv);
            envblock = Advapi32Util.getEnvironmentBlock(mergedEnv);
        } else {
            envblock = setupRunAsAnotherUser(username, password, defaultEnv, overrideEnv, extraInfo);
        }

        // init handles
        WinNT.HANDLE ret = new WinNT.HANDLE(Pointer.createConstant(0));
        WinNT.HANDLEByReference[] handles = new WinNT.HANDLEByReference[stdHandles.length];
        for (int i = 0; i < stdHandles.length; i++) {
            handles[i] = new WinNT.HANDLEByReference(new WinNT.HANDLE(Pointer.createConstant(stdHandles[i])));
        }

        if (cmd != null) {
            // Globally synchronize process creation to avoid processes inheriting the wrong handles.
            // https://github.com/aws-greengrass/aws-greengrass-nucleus/pull/1098
            synchronized (Kernel32.INSTANCE) {
                ret = processCreate(username, password, cmd, envblock, path, handles, redirectErrorStream, extraInfo,
                        processCreationFlags);
            }
        }

        for (int i = 0; i < stdHandles.length; i++) {
            stdHandles[i] = getPointerLongValue(handles[i].getPointer());
        }

        return ret;
    }

    private static long getPointerLongValue(Pointer p) {
        if (POINTER_SIZE == 4) {
            return p.getInt(0);
        }
        return p.getLong(0);
    }

    private static int getExitCodeProcess(WinNT.HANDLE handle) {
        IntByReference exitStatus = new IntByReference();
        if (!Kernel32.INSTANCE.GetExitCodeProcess(handle, exitStatus)) {
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        }
        return exitStatus.getValue();
    }

    /*
     * Method modified by Amazon to use a different exit code and log the error
     */
    private static void terminateProcess(WinNT.HANDLE handle) {
        if (!Kernel32.INSTANCE.TerminateProcess(handle, EXIT_CODE_TERMINATED)) {
            logger.debug("Terminate process failed {}", Kernel32Util.getLastErrorMessage());
        }
    }

    /*
     * Method modified by Amazon to log the error
     */
    private static boolean isProcessAlive(WinNT.HANDLE handle) {
        IntByReference exitStatus = new IntByReference();
        if (!Kernel32.INSTANCE.GetExitCodeProcess(handle, exitStatus)) {
            int errorCode = Kernel32.INSTANCE.GetLastError();
            logger.error("GetExitCodeProcess failed {}", Kernel32Util.formatMessageFromLastErrorCode(errorCode));
            throw new LastErrorException(errorCode);
        }
        return exitStatus.getValue() == STILL_ACTIVE;
    }

    private static void closeHandle(WinNT.HANDLE handle) {
        if (!handle.equals(INVALID_HANDLE_VALUE)) {
            Kernel32Util.closeHandle(handle);
        }
    }

    /**
     * Opens a file for atomic append. The file is created if it doesn't
     * already exist.
     *
     * @param path the file to open or create
     * @return the native HANDLE
     */
    private static long openForAtomicAppend(String path) throws IOException {
        int access = WinNT.GENERIC_READ | WinNT.GENERIC_WRITE;
        int sharing = WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE;
        int disposition = WinNT.OPEN_ALWAYS;
        int flagsAndAttributes = WinNT.FILE_ATTRIBUTE_NORMAL;
        if (path == null || path.isEmpty()) {
            return -1;
        } else {
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path, access, sharing, null, disposition, flagsAndAttributes, null);
            if (handle == WinBase.INVALID_HANDLE_VALUE) {
                throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
            }
            return getPointerLongValue(handle.getPointer());
        }
    }

    private static void waitForTimeoutInterruptibly(WinNT.HANDLE handle, long timeout) {
        int result = Kernel32.INSTANCE.WaitForMultipleObjects(1, new WinNT.HANDLE[]{handle}, false, (int) timeout);
        if (result == WinBase.WAIT_FAILED) {
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        }
    }

    /*
     * Begin Amazon addition.
     */

    /**
     * Preparation for running process as another user. Populates the given extraInfo if applicable.
     * @return environment block for CreateProcess* APIs
     */
    private String setupRunAsAnotherUser(String username, char[] password, Map<String, String> defaultEnv,
                                         Map<String, String> overrideEnv, ProcessCreationExtras extraInfo)
            throws ProcessCreationException {
        // Get and cache process token and isService state
        synchronized (Advapi32.INSTANCE) {
            if (processToken.get() == null) {
                WinNT.HANDLEByReference processTokenHandle = new WinNT.HANDLEByReference();
                if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                        Kernel32.TOKEN_ALL_ACCESS, processTokenHandle)) {
                    throw lastErrorProcessCreationException("OpenProcessToken");
                }
                processToken.set(processTokenHandle);
                isService.set(checkIsService(processTokenHandle));
                // If we are a service then we will need these privileges. Escalate now so we only do it once
                if (isService.get()) {
                    enablePrivileges(processTokenHandle.getValue(),
                            Kernel32.SE_TCB_NAME, Kernel32.SE_ASSIGNPRIMARYTOKEN_NAME);
                }
            }
        }

        // LogonUser
        boolean logonSuccess = false;
        WinNT.HANDLEByReference userTokenHandle = new WinNT.HANDLEByReference();
        int[] logonTypes =
                {Kernel32.LOGON32_LOGON_INTERACTIVE, Kernel32.LOGON32_LOGON_SERVICE, Kernel32.LOGON32_LOGON_BATCH};
        for (int logonType : logonTypes) {
            if (Advapi32.INSTANCE.LogonUser(username, null, new String(password), logonType,
                    Kernel32.LOGON32_PROVIDER_DEFAULT, userTokenHandle)) {
                logonSuccess = true;
                break;
            }
        }
        if (!logonSuccess) {
            throw lastErrorProcessCreationException("LogonUser");
        }

        // If is service, create a duplicate "primary token" with proper security attributes for CreateProcessAsUser
        final WinNT.HANDLEByReference primaryTokenHandle = new WinNT.HANDLEByReference();
        if (isService.get()) {
            // Init security descriptor. 64 KB is guaranteed large enough for SecurityDescriptor
            // https://docs.microsoft.com/en-us/windows-hardware/drivers/ddi/fltkernel/nf-fltkernel-fltquerysecurityobject#remarks
            final WinNT.SECURITY_DESCRIPTOR securityDescriptor = new WinNT.SECURITY_DESCRIPTOR(64 * 1024);
            if (!Advapi32.INSTANCE.InitializeSecurityDescriptor(securityDescriptor, WinNT.SECURITY_DESCRIPTOR_REVISION)) {
                throw lastErrorProcessCreationException("InitializeSecurityDescriptor");
            }

            // NULL DACL is assigned to the security descriptor, which allows all access to the object
            if (!Advapi32.INSTANCE.SetSecurityDescriptorDacl(securityDescriptor, true, null, false)) {
                throw lastErrorProcessCreationException("SetSecurityDescriptorDacl");
            }

            // Duplicate userToken to create a "primary token" for CreateProcessAsUser
            final WinBase.SECURITY_ATTRIBUTES processSa = new WinBase.SECURITY_ATTRIBUTES();
            processSa.lpSecurityDescriptor = securityDescriptor.getPointer();
            processSa.bInheritHandle = true;
            processSa.write();
            // DesiredAccess 0 to request the same access rights as the existing token
            if (!Advapi32.INSTANCE.DuplicateTokenEx(userTokenHandle.getValue(), 0, processSa,
                    WinNT.SECURITY_IMPERSONATION_LEVEL.SecurityImpersonation, WinNT.TOKEN_TYPE.TokenPrimary,
                    primaryTokenHandle)) {
                throw lastErrorProcessCreationException("DuplicateTokenEx");
            }

            extraInfo.processSa = processSa;
            extraInfo.primaryTokenHandle = primaryTokenHandle.getValue();
        }

        // Load user profile
        final UserEnv.PROFILEINFO profileInfo = new UserEnv.PROFILEINFO();
        profileInfo.lpUserName = username;
        profileInfo.dwSize = profileInfo.size();
        profileInfo.write();
        if (!UserEnv.INSTANCE.LoadUserProfile(userTokenHandle.getValue(), profileInfo)) {
            logger.warn("Unable to load user profile. Some environment variables may not be accessible",
                    lastErrorRuntimeException());
        }

        String envblock = computeEnvironmentBlock(userTokenHandle.getValue(), defaultEnv, overrideEnv);
        Kernel32Util.closeHandleRefs(userTokenHandle);
        return envblock;
    }

    private static ProcessCreationException lastErrorProcessCreationException(String context) {
        return lastErrorProcessCreationException(context, Kernel32.INSTANCE.GetLastError());
    }

    private static ProcessCreationException lastErrorProcessCreationException(String context, int errorCode) {
        return new ProcessCreationException(String.format("[%s] %s", context,
                Kernel32Util.formatMessageFromLastErrorCode(errorCode)));
    }

    private static LastErrorException lastErrorRuntimeException() {
        return new LastErrorException(Kernel32.INSTANCE.GetLastError());
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

    private static boolean checkIsService(WinNT.HANDLEByReference processToken) throws ProcessCreationException {
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

    /**
     * Convert environment Map to lpEnvironment block format.
     *
     * @return environment block for starting a process
     * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-createenvironmentblock">docs</a>
     */
    private static String computeEnvironmentBlock(WinNT.HANDLE userTokenHandle, Map<String, String> defaultEnv,
                                                  Map<String, String> overrideEnv) throws ProcessCreationException {
        PointerByReference lpEnv = new PointerByReference();
        // Get user's env vars, inheriting current process env
        // It returns pointer to a block of null-terminated strings. It ends with two nulls (\0\0).
        if (!UserEnv.INSTANCE.CreateEnvironmentBlock(lpEnv, userTokenHandle, true)) {
            throw lastErrorProcessCreationException("CreateEnvironmentBlock");
        }

        // Windows env var keys are case-insensitive. Use case-insensitive map to avoid collision
        // The resulting env is merged from defaultEnv, the given user's env (returned by CreateEnvironmentBlock),
        // and overrideEnv. They are merged in that order so that later envs have higher precedence in case
        // a key is defined in multiple places.
        Map<String, String> mergedEnv = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        mergedEnv.putAll(defaultEnv);

        int offset = 0;
        while (true) {
            String s = lpEnv.getValue().getWideString(offset);
            if (s.length() == 0) {
                break;
            }
            // wide string uses 2 bytes per char. +2 to skip the terminating null
            offset += s.length() * 2 + 2;
            int splitInd = s.indexOf('=');
            mergedEnv.put(s.substring(0, splitInd), s.substring(splitInd + 1));
        }

        if (!UserEnv.INSTANCE.DestroyEnvironmentBlock(lpEnv.getValue())) {
            throw lastErrorProcessCreationException("DestroyEnvironmentBlock");
        }

        mergedEnv.putAll(overrideEnv);

        return Advapi32Util.getEnvironmentBlock(mergedEnv);
    }

    private static class ProcessCreationExtras {
        // for CreateProcessAsUser:
        private WinBase.SECURITY_ATTRIBUTES processSa;
        private WinNT.HANDLE primaryTokenHandle;
    }

    /*
     * End Amazon addition.
     */
}
