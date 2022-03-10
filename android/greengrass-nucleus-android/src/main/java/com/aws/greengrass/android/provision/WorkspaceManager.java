/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.NonNull;

public class WorkspaceManager {
    private static final String ROOT_FOLDER = "/greengrass/v2";
    private static final String CONFIG_FOLDER = "config";

    private static String baseFolder;
    private static WorkspaceManager instance = null;

    private WorkspaceManager(@NonNull File filesDir) {
        baseFolder = filesDir.toString();
        // build greengrass v2 path and create it
        File greengrassV2 = Paths.get(baseFolder, ROOT_FOLDER).toFile();
        greengrassV2.mkdirs();
        Paths.get(baseFolder, ROOT_FOLDER, CONFIG_FOLDER).toFile().mkdirs();

        System.setProperty("root", greengrassV2.getAbsolutePath());
    }

    /**
     * Initializes workspace folders and returns instance
     *
     * @param filesDir Android's application files directory
     * @return instance of WorkspaceManager
     */
    public static synchronized WorkspaceManager getInstance(@NonNull File filesDir) {
        if (instance == null) {
            instance = new WorkspaceManager(filesDir);
        }
        return instance;
    }

    /**
     * Returns Greengrass root directory path in Android application
     *
     * @return Greengrass root path directory path
     */
    public Path getRootPath() {
        return Paths.get(baseFolder, ROOT_FOLDER);
    }

    /**
     * Returns config directory path in Android application
     *
     * @return config directory path
     */
    public static Path getConfigPath() {
        return Paths.get(baseFolder, ROOT_FOLDER, CONFIG_FOLDER);
    }
}
