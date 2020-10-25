/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UniqueRootPathExtension implements BeforeEachCallback, BeforeAllCallback {
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
                    Utils.deleteFileRecursively(p.toFile());
                }
            };
        } catch (IOException e) {
            throw new ExtensionConfigurationException("Couldn't create temp directory", e);
        }
    }
}
