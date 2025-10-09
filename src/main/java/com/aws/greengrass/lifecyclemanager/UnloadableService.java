/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.amazon.aws.iot.greengrass.component.common.ComponentType.PLUGIN;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.dependency.EZPlugins.JAR_FILE_EXTENSION;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;

public class UnloadableService extends GreengrassService {
    ServiceLoadException serviceLoadException;
    long exceptionTimestamp;

    /**
     * Create a new instance for unloadable service.
     *
     * @param topics root topic for this service
     * @param e the original exception while loading the service
     */
    public UnloadableService(Topics topics, ServiceLoadException e) {
        super(topics);
        this.serviceLoadException = e;
        this.exceptionTimestamp = Instant.now().toEpochMilli();
        logger.atError().log(exceptionTimestamp);
    }

    @Override
    protected void install() {
        serviceErrored(serviceLoadException.getMessage());
    }

    @Override
    public int bootstrap() {
        return REQUEST_RESTART;
    }

    @Override
    public boolean isBootstrapRequired(Map<String, Object> newServiceConfig) {
        if (!PLUGIN.name().equals(newServiceConfig.get(SERVICE_TYPE_TOPIC_KEY))) {
            logger.atInfo().log("Bootstrap is not required: component type is not Plugin");
            return false;
        }
        if (!newServiceConfig.containsKey(VERSION_CONFIG_KEY)) {
            logger.atInfo().log("Bootstrap is not required: config incomplete with no version");
            return false;
        }
        String newVersion = newServiceConfig.get(VERSION_CONFIG_KEY).toString();
        if (Utils.stringHasChanged(Coerce.toString(getConfig().find(VERSION_CONFIG_KEY)), newVersion)) {
            logger.atInfo().log("Bootstrap is required: plugin version changed");
            return true;
        }
        try {
            Path pluginJar = config.getContext()
                    .get(NucleusPaths.class)
                    .artifactPath(new ComponentIdentifier(getName(), new Semver(newVersion)))
                    .resolve(getName() + JAR_FILE_EXTENSION);

            if (!pluginJar.toFile().exists() || !pluginJar.toFile().isFile()) {
                logger.atInfo().kv("pluginJar", pluginJar).log("Bootstrap is not required: plugin JAR not found");
                return false;
            }

            if (Files.getLastModifiedTime(pluginJar).toMillis() > exceptionTimestamp) {
                logger.atInfo().kv("pluginJar", pluginJar).log("Bootstrap is required: plugin JAR was modified");
                return true;
            }
            logger.atInfo().kv("pluginJar", pluginJar).log("Bootstrap is not required: no change in plugin JAR");
        } catch (IOException | SemverException e) {
            logger.atError().log("Bootstrap is not required: unable to locate plugin JAR", e);
        }
        return false;
    }

    /**
     * Moves the service to finished state and shuts down lifecycle thread. Since the service has loading exceptions,
     * don't expect depending services to exit before itself.
     *
     * @return future completes when the lifecycle thread shuts down.
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public CompletableFuture<Void> close() {
        return close(false);
    }
}
