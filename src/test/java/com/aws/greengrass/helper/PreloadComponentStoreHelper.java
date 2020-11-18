/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.helper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Base64;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@NoArgsConstructor(access = AccessLevel.PRIVATE) // so that it can't be 'new'
public class PreloadComponentStoreHelper {

    /**
     * @param testResourceRecipeDir   contains recipes with file naming convention: {name}-{version}.yaml
     * @param componentStoreRecipeDir component store recipe folder
     */
    public static void preloadRecipesFromTestResourceDir(Path testResourceRecipeDir, Path componentStoreRecipeDir)
            throws IOException {
        Files.walkFileTree(testResourceRecipeDir, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // do nothing because expect the input testResourceRecipeDir only contains recipe files
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // parse file name
                String recipeStorageName = getRecipeStorageFilenameFromTestSource(file.toFile().getName());

                Files.copy(file, componentStoreRecipeDir.resolve(recipeStorageName), REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * @param recipeFileName test recipe file name with naming convention: {componentName}-{version}.yaml
     * @return the storage file name for recipe in the form of {hash}@{version}.recipe.yaml
     */
    public static String getRecipeStorageFilenameFromTestSource(String recipeFileName) {
        // The test recipe file name is in the form of {componentName}-{version}.yaml
        String componentName = recipeFileName.split("-")[0];
        String version = recipeFileName.split("-")[1].split(".yaml")[0];

        // destination should be {hash}@{version}.recipe.yaml
        String hash = getHashFromComponentName(componentName);

        return String.format("%s@%s.recipe.yaml", hash, version);
    }

    @SneakyThrows
    // @SneakyThrows is used since the test should just fail when checked NoSuchAlgorithmException is thrown
    public static String getHashFromComponentName(String componentName) {
        // expects the hash of component name to be base64 (url safe and no padding) encoded SHA256
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(componentName.getBytes(StandardCharsets.UTF_8)));
    }
}
