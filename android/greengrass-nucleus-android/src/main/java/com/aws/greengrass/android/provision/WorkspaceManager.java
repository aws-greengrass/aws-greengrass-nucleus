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
import lombok.experimental.UtilityClass;

@UtilityClass
public class WorkspaceManager {
    private static final String ROOT_FOLDER = "/greengrass/v2";
    private static final String CONFIG_FOLDER = "config";

    private static String baseFolder;
    private static ReentrantLock lock = new ReentrantLock();
    private static AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Initializes Greengrass workspace
     *
     * @param filesDir Android's application files directory
     */
    public static void init(@NonNull File filesDir) {
        lock.lock();
        try {
            if (!initialized.get()) {
                baseFolder = filesDir.toString();
                // build greengrass v2 path and create it
                File greengrassV2 = Paths.get(baseFolder, ROOT_FOLDER).toFile();
                greengrassV2.mkdirs();
                Paths.get(baseFolder, ROOT_FOLDER, CONFIG_FOLDER).toFile().mkdirs();

                System.setProperty("root", greengrassV2.getAbsolutePath());
                initialized.set(true);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns Greengrass root directory path in Android application
     *
     * @return Greengrass root path directory path
     * @throws RuntimeException
     */
    public static Path getRootPath() throws RuntimeException {
        if (initialized.get()) {
            return Paths.get(baseFolder, ROOT_FOLDER);
        } else {
            throw new RuntimeException("Greengrass workspace is not initialized");
        }
    }

    /**
     * Returns config directory path in Android application
     *
     * @return config directory path
     * @throws RuntimeException
     */
    public static Path getConfigPath() throws RuntimeException {
        if (initialized.get()) {
            return Paths.get(baseFolder, ROOT_FOLDER, CONFIG_FOLDER);
        } else {
            throw new RuntimeException("Greengrass workspace is not initialized");
        }
    }
}
