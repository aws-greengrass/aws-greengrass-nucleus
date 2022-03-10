/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import com.aws.greengrass.android.provision.WorkspaceManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServicesConfigurationProvider {
    private static ServicesConfigurationProvider instance = null;

    private ServicesConfigurationProvider() {}

    /**
     * Gets instance of ServicesConfigurationProvider
     */
    public static synchronized ServicesConfigurationProvider getInstance() {
        if (instance == null) {
            instance = new ServicesConfigurationProvider();
        }
        return  instance;
    }

    /**
     * Provides external services config to Nucleus
     *
     * @param is external config input stream
     */
    public void setExternalConfig(InputStream is) {
        File destinationFile = Paths.get(WorkspaceManager.getConfigPath().toString(), "config.yaml").toFile();
        try {
            if (!destinationFile.exists()) {
                destinationFile.mkdirs();
            }
            Files.copy(is, destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
