/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * JNA bindings for Windows userenv native library.
 * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/">userenv.h</a>
 */
@SuppressWarnings("PMD")
@SuppressFBWarnings
public interface UserEnv extends StdCallLibrary {
    UserEnv INSTANCE = Native.load("userenv", UserEnv.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * Load user's profile.
     *
     * @param hToken Token for the user, which is returned by the LogonUser, CreateRestrictedToken, DuplicateToken,
     *               OpenProcessToken, or OpenThreadToken function. The token must have TOKEN_QUERY,
     *               TOKEN_IMPERSONATE, and TOKEN_DUPLICATE access. For more information, see Access Rights for
     *               Access-Token Objects.
     * @param profile Pointer to a PROFILEINFO structure. LoadUserProfile fails and returns ERROR_INVALID_PARAMETER
     *                if the dwSize member of the structure is not set to sizeof(PROFILEINFO) or if the lpUserName
     *                member is NULL. For more information
     * @return <code>true</code> if successful; otherwise <code>false</code>
     * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-loaduserprofilea">Docs</a>
     */
    boolean LoadUserProfile(WinNT.HANDLE hToken, PROFILEINFO profile);

    /**
     * Retrieves the environment variables for the specified user.
     * This block can then be passed to the CreateProcessAsUser function.
     *
     * @param lpEnvironment When this function returns, receives a pointer to the new environment block.
     *                      The environment block is an array of null-terminated Unicode strings.
     *                      The list ends with two nulls (\0\0).
     * @param hToken Token for the user, returned from the LogonUser function. If this is a primary token,
     *               the token must have TOKEN_QUERY and TOKEN_DUPLICATE access. If the token is an impersonation
     *               token, it must have TOKEN_QUERY access. For more information, see Access Rights for Access-Token
     *               Objects. If this parameter is NULL, the returned environment block contains system variables only.
     * @param bInherit Specifies whether to inherit from the current process' environment. If this value is TRUE,
     *                 the process inherits the current process' environment. If this value is FALSE, the process
     *                 does not inherit the current process' environment.
     * @return <code>true</code> if successful; otherwise <code>false</code>
     * @see <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-createenvironmentblock">Docs</a>
     */
    boolean CreateEnvironmentBlock(PointerByReference lpEnvironment, WinNT.HANDLE hToken, boolean bInherit);

    /**
     * Frees environment variables created by the CreateEnvironmentBlock function.
     *
     * @param lpEnvironment Pointer to the environment block created by CreateEnvironmentBlock.
     * @return <code>true</code> if successful; otherwise <code>false</code>
     * @see
     * <a href="https://docs.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-destroyenvironmentblock">Docs</a>
     */
    boolean DestroyEnvironmentBlock(Pointer lpEnvironment);

    /**
     * typedef struct _PROFILEINFOA {
     *   DWORD             dwSize;
     *   DWORD             dwFlags;
     *   MIDL_STRING LPSTR lpUserName;
     *   MIDL_STRING LPSTR lpProfilePath;
     *   MIDL_STRING LPSTR lpDefaultPath;
     *   MIDL_STRING LPSTR lpServerName;
     *   MIDL_STRING LPSTR lpPolicyPath;
     * #if ...
     *   ULONG_PTR         hProfile;
     * #else
     *   HANDLE            hProfile;
     * #endif
     * } PROFILEINFOA, *LPPROFILEINFOA;
     */
    @Structure.FieldOrder(
            {"dwSize", "dwFlags", "lpUserName", "lpProfilePath", "lpDefaultPath", "lpServerName", "lpPolicyPath",
                    "hProfile"})
    public static class PROFILEINFO extends Structure {
        public int dwSize;
        public int dwFlags;
        public String lpUserName;
        public String lpProfilePath;
        public String lpDefaultPath;
        public String lpServerName;
        public String lpPolicyPath;
        public WinNT.HANDLE hProfile;
    }
}
