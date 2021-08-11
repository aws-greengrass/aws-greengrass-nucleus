/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.CrashableFunction;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.FileSystemPermission.Option;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.unix.DarwinPlatform;
import com.aws.greengrass.util.platforms.unix.QNXPlatform;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;
import com.aws.greengrass.util.platforms.unix.linux.LinuxPlatform;
import com.aws.greengrass.util.platforms.windows.WindowsPlatform;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static com.aws.greengrass.config.PlatformResolver.OS_DARWIN;
import static com.aws.greengrass.config.PlatformResolver.OS_LINUX;

public abstract class Platform implements UserPlatform {

    public static final Logger logger = LogManager.getLogger(Platform.class);
    public static final String SET_PERMISSIONS_EVENT = "set-permissions";
    public static final String PATH = "path";

    private static Platform INSTANCE;

    /**
     * Get the appropriate instance of Platform for the current platform.
     *
     * @return Platform
     */
    public static synchronized Platform getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        if (PlatformResolver.isWindows) {
            INSTANCE = new WindowsPlatform();
        } else if (OS_DARWIN.equals(PlatformResolver.getOSInfo())) {
            INSTANCE = new DarwinPlatform();
        } else if (System.getProperty("os.name").toLowerCase().contains("qnx")) {
            INSTANCE = new QNXPlatform();
        } else if (OS_LINUX.equals(PlatformResolver.getOSInfo())) {
            INSTANCE = new LinuxPlatform();
        } else {
            INSTANCE = new UnixPlatform();
        }

        logger.atInfo().log("Getting platform instance {}.", INSTANCE.getClass().getName());

        return INSTANCE;
    }

    public abstract Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                                        UserOptions decorator)
            throws IOException, InterruptedException;

    public abstract ShellDecorator getShellDecorator();

    public abstract int exitCodeWhenCommandDoesNotExist();

    public abstract UserOptions getUserDecorator();

    public abstract String getPrivilegedGroup();

    public abstract String getPrivilegedUser();

    public abstract RunWithGenerator getRunWithGenerator();

    public abstract void createUser(String user) throws IOException;

    public abstract void createGroup(String group) throws IOException;

    public abstract void addUserToGroup(String user, String group) throws IOException;

    public abstract SystemResourceController getSystemResourceController();

    public abstract Exec createNewProcessRunner();

    /**
     * Set permissions on a path.
     *
     * @param permission permissions to set
     * @param path       path to apply to
     * @param options    options for how to apply the permission to the path - if none, then the mode is set
     * @throws IOException if any exception occurs while changing permissions
     */
    public void setPermissions(FileSystemPermission permission, Path path,
                                        Option... options) throws IOException {
        // convert to set for easier checking of set options
        EnumSet<Option> set = options.length == 0 ? EnumSet.of(Option.SetMode) :
                EnumSet.copyOf(Arrays.asList(options));
        setPermissions(permission, path, set);
    }

    /**
     * Set permissions on a path. This changes the mode and owner.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @throws IOException if any exception occurs while changing permissions
     */
    public void setPermissions(FileSystemPermission permission, Path path) throws IOException {
        setPermissions(permission, path, EnumSet.of(Option.SetMode, Option.SetOwner));
    }

    /**
     * Set permission on a path.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @param options options for how to apply the permission to the path
     * @throws IOException if any exception occurs while changing permissions
     */
    protected void setPermissions(FileSystemPermission permission, Path path, EnumSet<Option> options)
            throws IOException {

        // noop function that does not set owner
        CrashableFunction<Path, Void, IOException> setOwner = (p) -> null;

        if (options.contains(Option.SetOwner)) {
            if (Utils.isEmpty(permission.getOwnerUser())) {
                logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH, path).log("No owner to set for path");
            } else {
                UserPrincipalLookupService lookupService = path.getFileSystem().getUserPrincipalLookupService();
                UserPrincipal userPrincipal = lookupService.lookupPrincipalByName(permission.getOwnerUser());
                GroupPrincipal groupPrincipal = Utils.isEmpty(permission.getOwnerGroup()) ? null :
                        lookupService.lookupPrincipalByGroupName(permission.getOwnerGroup());

                setOwner = (p) -> {
                    this.setOwner(userPrincipal, groupPrincipal, p);
                    return null;
                };
            }
        }

        // noop function that does not change the file mode
        CrashableFunction<Path, Void, IOException> setMode = (p) -> null;

        if (options.contains(Option.SetMode)) {
            FileSystemPermissionView view = getFileSystemPermissionView(permission, path);
            setMode = (p) -> {
                this.setMode(view, p);
                return null;
            };
        }

        final CrashableFunction<Path, Void, IOException> setModeFunc = setMode;
        final CrashableFunction<Path, Void, IOException> setOwnerFunc = setOwner;
        if (options.contains(Option.Recurse)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    setModeFunc.apply(dir);
                    setOwnerFunc.apply(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    setModeFunc.apply(file);
                    setOwnerFunc.apply(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            setModeFunc.apply(path);
            setOwnerFunc.apply(path);
        }
    }

    protected abstract void setOwner(UserPrincipal userPrincipal, GroupPrincipal groupPrincipal, Path path)
            throws IOException;

    protected abstract FileSystemPermissionView getFileSystemPermissionView(FileSystemPermission permission, Path path)
            throws IOException;

    protected abstract void setMode(FileSystemPermissionView permissionView, Path path) throws IOException;

    public abstract String prepareIpcFilepath(Path rootPath);

    public abstract String prepareIpcFilepathForComponent(Path rootPath);

    public abstract String prepareIpcFilepathForRpcServer(Path rootPath);

    public abstract void setIpcFilePermissions(Path rootPath);

    public abstract void cleanupIpcFiles(Path rootPath);

    protected static class FileSystemPermissionView {
    }
}
