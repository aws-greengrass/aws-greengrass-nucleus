/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;

import java.io.IOException;
import java.nio.file.Path;

import static com.aws.greengrass.componentmanager.ComponentStore.ARTIFACTS_DECOMPRESSED_DIRECTORY;
import static com.aws.greengrass.componentmanager.ComponentStore.ARTIFACT_DIRECTORY;
import static com.aws.greengrass.componentmanager.ComponentStore.RECIPE_DIRECTORY;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class NucleusPaths {
    private Path rootPath;
    private Path workPath;
    private Path componentStorePath;
    private Path configPath;
    private Path deploymentPath;
    private Path kernelAltsPath;
    private Path cliIpcInfoPath;
    private Path binPath;
    private Path logsPath;

    public void initPaths(Path root, Path workPath, Path componentStorePath, Path configPath, Path kernelAlts,
                          Path deployment, Path cliIpcInfo, Path binPath, Path logsPath) throws IOException {
        setRootPath(root);
        setConfigPath(configPath);
        setDeploymentPath(deployment);
        setKernelAltsPath(kernelAlts);
        setWorkPath(workPath);
        setComponentStorePath(componentStorePath);
        setCliIpcInfoPath(cliIpcInfo);
        setBinPath(binPath);
        setLogsPath(logsPath);
    }

    public void setLogsPath(Path logsPath) throws IOException {
        this.logsPath = logsPath;
        Utils.createPaths(logsPath());
        Permissions.setLogsPermission(logsPath());
    }

    public Path logsPath() {
        return logsPath;
    }

    public void setBinPath(Path binPath) throws IOException {
        this.binPath = binPath;
        Utils.createPaths(binPath());
        Permissions.setBinPermission(binPath());
    }

    public Path binPath() {
        return binPath;
    }

    public void setCliIpcInfoPath(Path cliIpcInfoPath) throws IOException {
        this.cliIpcInfoPath = cliIpcInfoPath;
        Utils.createPaths(cliIpcInfoPath());
        Permissions.setCliIpcInfoPermission(cliIpcInfoPath());
    }

    public Path cliIpcInfoPath() {
        return cliIpcInfoPath;
    }

    public void setKernelAltsPath(Path kernelAltsPath) throws IOException {
        this.kernelAltsPath = kernelAltsPath;
        Utils.createPaths(kernelAltsPath());
        Permissions.setKernelAltsPermission(kernelAltsPath());
    }

    public Path kernelAltsPath() {
        return kernelAltsPath;
    }

    public void setDeploymentPath(Path deploymentPath) throws IOException {
        this.deploymentPath = deploymentPath;
        Utils.createPaths(deploymentPath());
        Permissions.setDeploymentPermission(deploymentPath());
    }

    public Path deploymentPath() {
        return deploymentPath;
    }

    public void setConfigPath(Path configPath) throws IOException {
        this.configPath = configPath;
        Utils.createPaths(configPath());
        Permissions.setConfigPermission(configPath());
    }

    public Path configPath() {
        return configPath;
    }

    public void setWorkPath(Path workPath) throws IOException {
        this.workPath = workPath;
        Utils.createPaths(workPath);
        Permissions.setWorkPathPermission(workPath);
    }

    public void setRootPath(Path root) throws IOException {
        this.rootPath = root;
        Utils.createPaths(root);
        Permissions.setRootPermission(root);

        Utils.createPaths(pluginPath());
        Permissions.setPluginPermission(pluginPath());
    }

    public void setComponentStorePath(Path componentStorePath) throws IOException {
        this.componentStorePath = componentStorePath;
        Utils.createPaths(componentStorePath);
        Permissions.setComponentStorePermission(componentStorePath);

        Utils.createPaths(artifactPath());
        Permissions.setArtifactStorePermission(artifactPath());

        Utils.createPaths(unarchivePath());
        Permissions.setArtifactStorePermission(unarchivePath());

        Utils.createPaths(recipePath());
        Permissions.setRecipeStorePermission(recipePath());
    }

    public Path artifactPath() {
        return componentStorePath.resolve(ARTIFACT_DIRECTORY);
    }

    public Path artifactPath(ComponentIdentifier componentIdentifier) throws IOException {
        Path p = artifactPath().resolve(componentIdentifier.getName());
        Utils.createPaths(p);
        Permissions.setArtifactStorePermission(p);

        p = p.resolve(componentIdentifier.getVersion().getValue());
        Utils.createPaths(p);
        Permissions.setArtifactStorePermission(p);

        return p;
    }

    public Path recipePath() {
        return componentStorePath.resolve(RECIPE_DIRECTORY);
    }

    public Path unarchivePath() {
        return componentStorePath.resolve(ARTIFACTS_DECOMPRESSED_DIRECTORY);
    }

    public Path unarchiveArtifactPath(ComponentIdentifier componentIdentifier, String artifactName) throws IOException {
        Path p = unarchiveArtifactPath(componentIdentifier).resolve(artifactName);
        Utils.createPaths(p);
        Permissions.setArtifactStorePermission(p);
        return p;
    }

    public Path unarchiveArtifactPath(ComponentIdentifier componentIdentifier) throws IOException {
        Path p = unarchivePath().resolve(componentIdentifier.getName());
        Utils.createPaths(p);
        Permissions.setArtifactStorePermission(p);

        p = p.resolve(componentIdentifier.getVersion().getValue());
        Utils.createPaths(p);
        Permissions.setArtifactStorePermission(p);
        return p;
    }

    public Path componentStorePath() {
        return componentStorePath;
    }

    public Path workPath(String serviceName) throws IOException {
        Path p = workPath().resolve(serviceName);
        Utils.createPaths(p);
        Permissions.setServiceWorkPathPermission(p);
        return p;
    }

    public Path workPath() {
        return workPath;
    }

    public Path rootPath() {
        return rootPath;
    }

    public Path pluginPath() {
        return rootPath.resolve("plugins");
    }

    public void setTelemetryPath(Path p) throws IOException {
        Utils.createPaths(p);
        Permissions.setTelemetryPermission(p);
    }

    public static void setLoggerPath(Path p) throws IOException {
        Utils.createPaths(p);
        Permissions.setLoggerPermission(p);
    }

    public Path nucleusLogsPath() {
        return logsPath.resolve("aws.greengrass.Nucleus.log");
    }
}
