/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.DefaultDeploymentTask;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PluginComponentTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private Kernel kernel;
    private PackageIdentifier ident;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_kernel_WHEN_locate_plugin_THEN_plugin_is_loaded_into_JVM() throws Exception {
        setupPackageStore();
        kernel.parseArgs("-i", this.getClass().getResource("plugin.yaml").toString());
        kernel.launch();

        EvergreenService eg = kernel.locate("plugin");
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginService", eg.getClass().getName());
        assertEquals(ident.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
    }

    @Test
    void GIVEN_kernel_WHEN_deploy_plugin_THEN_plugin_is_loaded_into_JVM(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        setupPackageStore();

        // launch kernel
        kernel.parseArgs().launch();

        submitSampleJobDocument(getClass().getResource("PluginDeployment.json").toURI(), System.currentTimeMillis(),
                kernel).get(30, TimeUnit.SECONDS);

        EvergreenService eg = kernel.locate("plugin");
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginService", eg.getClass().getName());
        assertEquals(ident.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
    }

    private void setupPackageStore() throws IOException, PackagingException {
        Path localStoreContentPath = Paths.get(getClass().getResource("local_store_content").getPath());
        Path e2eTestPkgStoreDir = tempRootDir.resolve("eteTestPkgStore");
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());
        PackageStore e2eTestPackageStore = new PackageStore(e2eTestPkgStoreDir);

        ident = new PackageIdentifier("plugin", new Semver("1.0.0"));
        Files.move(e2eTestPackageStore.resolveArtifactDirectoryPath(ident).resolve("plugin-tests.jar"),
                e2eTestPackageStore.resolveArtifactDirectoryPath(ident).resolve("plugin.jar"));
        kernel.getContext().put(PackageStore.class, e2eTestPackageStore);
    }

    private static Future<DeploymentResult> submitSampleJobDocument(URI uri, Long timestamp, Kernel kernel)
            throws Exception {
        DeploymentDocument sampleJobDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        sampleJobDocument.setTimestamp(timestamp);
        sampleJobDocument.setGroupName("ANY");
        PackageManager packageManager = kernel.getContext().get(PackageManager.class);
        DependencyResolver dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        KernelConfigResolver kernelConfigResolver = kernel.getContext().get(KernelConfigResolver.class);
        DeploymentConfigMerger deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DefaultDeploymentTask deploymentTask =
                new DefaultDeploymentTask(dependencyResolver, packageManager, kernelConfigResolver,
                        deploymentConfigMerger, LogManager.getLogger("Deployer"), sampleJobDocument,
                        Topics.of(kernel.getContext(), DeploymentService.DEPLOYMENT_SERVICE_TOPICS, null));
        return kernel.getContext().get(ExecutorService.class).submit(deploymentTask);
    }
}
