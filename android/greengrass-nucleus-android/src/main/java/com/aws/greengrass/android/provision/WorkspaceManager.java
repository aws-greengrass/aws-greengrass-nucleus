/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import lombok.NonNull;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkspaceManager {
    /** @// TODO: 28.04.2022 duplicated with KernelAlternatives. Also split to parts */
    private static final String ROOT_FOLDER = "/greengrass/v2";
    private static final String CONFIG_FOLDER = "config";
    private static final String UNARCHIVED_FOLDER = "packages/artifacts-unarchived";

    private static final String ALTS_FOLDER = "alts";
    private static final String CURRENT_FOLDER = "current";
    private static final String INIT_FOLDER = "init";

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
     * Initializes workspace folders and returns instance.
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
     * Returns Greengrass root directory path in Android application.
     *
     * @return Greengrass root path directory path
     */
    public Path getRootPath() {
        return Paths.get(baseFolder, ROOT_FOLDER);
    }

    /**
     * Returns config directory path in Android application.
     *
     * @return config directory path
     */
    public static Path getConfigPath() {
        return Paths.get(baseFolder, ROOT_FOLDER, CONFIG_FOLDER);
    }

    /**
     * Returns unarchived directory path in Android application.
     *
     * @return unarchived directory path
     */
    public static Path getUnarchivedPath() {
        return Paths.get(baseFolder, ROOT_FOLDER, UNARCHIVED_FOLDER);
    }

    /**
     * Returns current directory path in Android application.
     *
     * @return current directory path
     */
    public static Path getCurrentPath() {
        return Paths.get(baseFolder, ROOT_FOLDER, ALTS_FOLDER, CURRENT_FOLDER);
    }

    /**
     * Returns init directory path in Android application.
     *
     * @return init directory path
     */
    public static Path getInitPath() {
        return Paths.get(baseFolder, ROOT_FOLDER, ALTS_FOLDER, INIT_FOLDER);
    }
}
