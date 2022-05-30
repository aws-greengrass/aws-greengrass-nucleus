/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.resources;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
#if ANDROID
#endif
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class TestResources {
    private static final Logger log = LogManager.getLogger(TestResources.class.getName());
    private static volatile TestResources instance;

    protected TestResources() {}

    /**
     * Main method to get instance of the class
     * @return {@link com.aws.greengrass.resources.TestResources} instance
     */
    public static TestResources getInstance() {
            if (instance == null) {
                synchronized (TestResources.class) {
                    if (instance == null) {
                        instance = createInstance();
                    }
                }
        }
            return instance;
    }

    private static TestResources createInstance() {
#if ANDROID
          if (PlatformResolver.isAndroid) {
              return new AndroidTestResources();
          }
#endif
          return new TestResources();

    }

    /**
     * Getting resource file that is defined as resource in source set
     * @param filename name of the resource file
     * @param clazz Type the resource file should be loaded within
     * @return {@link Path} the required resource can be accessed with
     */
    public Path getResource(String filename, Class<?> clazz) {
        Path path = null;
        try {
            path = Paths.get(Objects.requireNonNull(clazz.getResource(filename)).toURI());
        } catch (URISyntaxException e) {
            logAndThrowResourceException(filename, e);
        }
        return path;
    }

    protected void logAndThrowResourceException(String filename, Throwable e) {
        log.atError("Error reading resource file: " + filename, e).log();
        e.fillInStackTrace();
        throw new ResourceNotFoundException("Unable to find resource " + filename +
                " on android", e);
    }
}
