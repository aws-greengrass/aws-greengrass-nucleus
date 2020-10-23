/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.FileVisitResult.CONTINUE;

final class UniqueRootStoreHelper {
    static final String KEY = "root";

    private UniqueRootStoreHelper() {
        
    }

    public static CloseableResource createPath(String key) {
        try {
            Path p = Files.createTempDirectory("greengrass-test");
            System.setProperty(key, p.toAbsolutePath().toString());
            return new CloseableResource() {
                @Override
                public void close() throws Throwable {
                    System.clearProperty(key);
                    Files.walkFileTree(p, new SimpleFileVisitor<Path>() {

                        @Override
                        public FileVisitResult visitFile(Path file,
                                                         BasicFileAttributes attrs) throws IOException {

                            Files.delete(file);
                            return CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir,
                                                                  IOException exc) throws IOException {
                            if (exc == null) {
                                Files.delete(dir);
                                return CONTINUE;
                            } else {
                                throw exc;
                            }
                        }
                    });
                }
            };
        } catch (IOException e) {
            throw new ExtensionConfigurationException("Couldn't create temp directory", e);
        }
    }
}
