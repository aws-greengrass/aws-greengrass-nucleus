/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class ComponentTestResourceHelper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
    public static final String MONITORING_SERVICE_PACKAGE_NAME = "MonitoringService";
    public static final String LOG_PACKAGE_NAME = "Log";
    public static final String COOL_DB_PACKAGE_NAME = "Cool-Database";

    static {
        OBJECT_MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    }

    private ComponentTestResourceHelper() {
    }

    public static Path getPathForLocalTestCache() {
        try {
            Path path = Paths.get(ComponentTestResourceHelper.class.getResource("plugins").toURI())
                    .resolve("test_cache_local");
            if (Files.notExists(path)) {
                Files.createDirectories(path);
            }
            return path;
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Failed to create local directory for test", e);
        }
    }

    public static Path getPathForTestPackage(String testPackageName, String testPackageVersion)
            throws URISyntaxException {
        Path rootPath = Paths.get(ComponentTestResourceHelper.class.getResource("test_packages").toURI());
        return rootPath.resolve(testPackageName + "-" + testPackageVersion);
    }

    public static Path getArtifactForTestPackage(String testPackageName, String testPackageVersion,
            String testArtifactName) throws URISyntaxException {
        return getPathForTestPackage(testPackageName, testPackageVersion).resolve(testArtifactName);
    }

    public static String getPackageRecipeForTestPackage(String testPackageName, String testPackageVersion)
            throws IOException, URISyntaxException {
        URI rootPath = ComponentTestResourceHelper.class.getResource("test_packages").toURI();
        Path path = Paths.get(rootPath).resolve(testPackageName + "-" + testPackageVersion).resolve("recipe.yaml");
        String recipeFmt = new String(Files.readAllBytes(path));
        return String.format(recipeFmt, rootPath.toString());
    }

    public static void cleanDirectory(Path pathToDirectory) throws IOException {
        if (Files.exists(pathToDirectory)) {
            Files.walkFileTree(pathToDirectory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
