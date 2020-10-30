/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class UniqueRootPathExtension implements BeforeEachCallback, BeforeAllCallback {
    private static final Logger logger = LogManager.getLogger(UniqueRootPathExtension.class);
    private static final Namespace NAMESPACE = Namespace.create(UniqueRootPathExtension.class);

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        extensionContext.getStore(NAMESPACE)
                .getOrComputeIfAbsent(extensionContext.getUniqueId(),
                    UniqueRootPathExtension::createPath,
                    CloseableResource.class);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        extensionContext.getStore(NAMESPACE)
                .getOrComputeIfAbsent(extensionContext.getUniqueId(),
                    UniqueRootPathExtension::createPath,
                    CloseableResource.class);
    }


    public static CloseableResource createPath(String key) {
        try {
            Path p = Files.createTempDirectory("greengrass-test");
            System.setProperty("root", p.toAbsolutePath().toString());
            return new CloseableResource() {
                @Override
                public void close() throws Throwable {
                    System.clearProperty("root");
                    FileSystemPermission permission =
                            FileSystemPermission.builder().ownerRead(true).ownerWrite(true).ownerExecute(true)
                                    .build();

                    // this visitor is necessary so that we can set permissions for everything to ensure it is
                    // writable before deleting
                    Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                            try {
                                Platform.getInstance().setPermissions(permission, dir);
                            } catch (IOException e) {
                                logger.atWarn().setCause(e).log("Could not set permissions on {}", dir);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            try {
                                Platform.getInstance().setPermissions(permission, file);
                            } catch (IOException e) {
                                logger.atWarn().setCause(e).log("Could not set permissions on {}", file);
                            }
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException e) {
                                logger.atWarn().setCause(e).log("Could not delete {}", file);
                                throw e;
                            }

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            try {
                                Files.deleteIfExists(dir);
                            } catch (IOException e) {
                                logger.atWarn().setCause(e).log("Could not delete {}", dir);
                                throw e;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            };
        } catch (IOException e) {
            throw new ExtensionConfigurationException("Couldn't create temp directory", e);
        }
    }
}
