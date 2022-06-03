/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.util.Log;
import com.aws.greengrass.android.provision.WorkspaceManager;
import lombok.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class ServicesConfigurationProvider {
    private static ServicesConfigurationProvider instance = null;
    final WorkspaceManager workspace;

    private ServicesConfigurationProvider(@NonNull File filesDir) {
        workspace = WorkspaceManager.getInstance(filesDir);
    }

    /**
     * Gets instance of ServicesConfigurationProvider.
     * @param filesDir path to application's files/ directory
     * @return instance of ServicesConfigurationProvider
     */
    public static synchronized ServicesConfigurationProvider getInstance(@NonNull File filesDir) {
        if (instance == null) {
            instance = new ServicesConfigurationProvider(filesDir);
        }
        return  instance;
    }

    /**
     * Provides external services config to Nucleus.
     *
     * @param is external config input stream
     */
    public void setExternalConfig(InputStream is) {
        File destinationFile = Paths.get(workspace.getConfigPath().toString(), "config.yaml").toFile();
        try {
            if (!destinationFile.exists()) {
                destinationFile.mkdirs();
            }
            Files.copy(is, destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.e("ServicesConfigurationProvider", e.toString());
        }
    }
}
