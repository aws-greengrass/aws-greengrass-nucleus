/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.resources;

import androidx.test.core.app.ApplicationProvider;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class AndroidTestResources extends TestResources {

    public Path getResource(String filename, Class<?> clazz) {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(Objects.requireNonNull(
                                clazz.getPackage())
                        .getName()
                        .replace('.', '/') + "/" + filename)));
             BufferedWriter file = Files.newBufferedWriter(Paths.get( new File(ctx.getFilesDir(), filename)
                     .getAbsolutePath()));
             BufferedWriter outputStream = new BufferedWriter(file)) {
            String mLine;
            while ((mLine = reader.readLine()) != null) {
                outputStream.write(mLine);
                outputStream.newLine();
            }
            outputStream.flush();
        } catch (IOException e) {
            logAndThrowResourceException(filename, e);
        }
        return new File(ctx.getFilesDir(), filename).toPath();
    }
}
