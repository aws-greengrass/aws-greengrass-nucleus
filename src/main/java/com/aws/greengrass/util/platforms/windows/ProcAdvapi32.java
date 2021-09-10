/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;


import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

/**
 * Customized JNA bindings for Windows Advapi32 native library.
 */
@SuppressWarnings("PMD")
public interface ProcAdvapi32 extends Library {
    ProcAdvapi32 INSTANCE = Native.load("Advapi32", ProcAdvapi32.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * Different from the JNA provided interface, we load this API such that the password is passed in as char[] so we
     * can quickly zero it out in memory. The LogonUser function attempts to log a user on to the local computer. The
     * local computer is the computer from which LogonUser was called. You cannot use LogonUser to log on to a remote
     * computer. You specify the user with a user name and domain, and authenticate the user with a plaintext password.
     * If the function succeeds, you receive a handle to a token that represents the logged-on user. You can then use
     * this token handle to impersonate the specified user or, in most cases, to create a process that runs in the
     * context of the specified user.
     *
     * @param lpszUsername  A pointer to a null-terminated string that specifies the name of the user. This is the name
     *                      of the user account to log on to. If you use the user principal name (UPN) format,
     *                      user@DNS_domain_name, the lpszDomain parameter must be NULL.
     * @param lpszDomain    A pointer to a null-terminated string that specifies the name of the domain or server whose
     *                      account database contains the lpszUsername account. If this parameter is NULL, the user name
     *                      must be specified in UPN format. If this parameter is ".", the function validates the
     *                      account using only the local account database.
     * @param lpszPassword  A pointer to a null-terminated string that specifies the plaintext password for the user
     *                      account specified by lpszUsername.
     * @param logonType     The type of logon operation to perform.
     * @param logonProvider Specifies the logon provider.
     * @param phToken       A pointer to a handle variable that receives a handle to a token that represents the
     *                      specified user.
     * @return If the function succeeds, the function returns nonzero. If the function fails, it returns zero. To get
     *         extended error information, call GetLastError.
     */
    boolean LogonUser(String lpszUsername, String lpszDomain, char[] lpszPassword, int logonType, int logonProvider,
                      WinNT.HANDLEByReference phToken);

    /**
     * Different from the JNA provided interface, we load this API such that `lpPassword` is passed in as a `char[]` so
     * we can quickly zero it out in memory. This does not work with multi-byte unicode characters. Using String or
     * WString does not work either. In JNA Advapi32 interface the password argument is a String.
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
