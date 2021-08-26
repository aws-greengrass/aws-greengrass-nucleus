/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;


import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.win32.W32APIOptions;

@SuppressWarnings({"PMD.ExcessiveParameterList", "PMD.UseObjectForClearerAPI", "PMD.MethodNamingConventions"})
public interface ProcAdvapi32 extends Library {
    ProcAdvapi32 INSTANCE = Native.load("Advapi32", ProcAdvapi32.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * Different from the JNA provided interface, we load this API such that
     * `lpPassword` is passed in as a `char[]` so we can quickly zero it out in memory. This does not work with
     * multi-byte unicode characters. Using String or WString does not work either.
     * In JNA Advapi32 interface the password argument is a String.
     *
     * @param lpUsername         name of the user
     * @param lpDomain           The name of the domain or server whose account database contains the lpUsername
     *                           account. If this parameter is NULL, the username must be specified in UPN format.
     * @param lpPassword         The clear-text password for the lpUsername account.
     * @param dwLogonFlags       The logon option
     * @param lpApplicationName  The name of the module to be executed.
     * @param lpCommandLine      The command line to be executed. The maximum length of this string is 1024 characters.
     *                           If lpApplicationName is NULL, the module name portion of lpCommandLine is limited to
     *                           MAX_PATH characters.
     * @param dwCreationFlags    The flags that control how the process is created.
     * @param lpEnvironment      A pointer to an environment block for the new process.If this parameter is NULL, the
     *                           new process uses an environment created from the profile of the user specified by
     *                           lpUsername.
     * @param lpCurrentDirectory The full path to the current directory for the process. The string can also specify a
     *                           UNC path.
     * @param lpStartupInfo      A pointer to a STARTUPINFO structure.
     * @param lpProcessInfo      A pointer to a PROCESS_INFORMATION structure that receives identification information
     *                           for the new process, including a handle to the process.
     * @return true if operation succeeded. False otherwise
     * @see <a href="http://msdn.microsoft.com/en-us/library/windows/desktop/ms682431%28v=vs.85%29.aspx">MSDN</a>
     */
    boolean CreateProcessWithLogonW(String lpUsername, String lpDomain, char[] lpPassword, int dwLogonFlags,
                                    String lpApplicationName, String lpCommandLine, int dwCreationFlags,
                                    String lpEnvironment, String lpCurrentDirectory, WinBase.STARTUPINFO lpStartupInfo,
                                    WinBase.PROCESS_INFORMATION lpProcessInfo);
}
