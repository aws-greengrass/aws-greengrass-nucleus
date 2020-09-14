/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.EZPlugins;
import com.aws.iot.evergreen.deployment.DefaultDeploymentTask;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.DeploymentDirectoryManager;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.activator.KernelUpdateActivator;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.deployment.model.ComponentUpdatePolicy;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.util.Coerce;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.iot.evergreen.dependency.EZPlugins.JAR_FILE_EXTENSION;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.model.ComponentUpdatePolicyAction.NOTIFY_COMPONENTS;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class PluginComponentTest extends BaseITCase {
    private static final String componentName = "plugin";
    private Kernel kernel;
    private PackageIdentifier componentId;

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

        EvergreenService eg = kernel.locate(componentName);
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.APluginService", eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernel.getContext().get(EZPlugins.class)
                .forName("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginDependency");
    }

    @Test
    void GIVEN_kernel_WHEN_locate_plugin_dependency_THEN_dependency_from_plugin_is_loaded_into_JVM() throws Exception {
        setupPackageStore();
        kernel.parseArgs("-i", this.getClass().getResource("plugin_dependency.yaml").toString());
        kernel.launch();

        EvergreenService eg = kernel.locate("plugin-dependency");
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginDependencyService",
                eg.getClass().getName());
    }

    @Test
    void GIVEN_kernel_WHEN_deploy_new_plugin_THEN_plugin_is_loaded_into_JVM(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        setupPackageStore();

        // launch kernel
        kernel.parseArgs().launch();

        // Ensure that the dependency isn't somehow in our class loader already
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.aws.iot.evergreen.integrationtests.kernel" + ".resource.PluginDependency"));

        submitSampleJobDocument(getPluginDeploymentDocument(System.currentTimeMillis(), "1.0.0",
                "f7fe5b16-574a-11ea-82b4-0242ac130004"), kernel).get(30, TimeUnit.SECONDS);

        EvergreenService eg = kernel.locate(componentName);
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.APluginService", eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernel.getContext().get(EZPlugins.class)
                .forName("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginDependency");
    }

    @Test
    void GIVEN_kernel_WHEN_deploy_updated_plugin_THEN_request_kernel_restart(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, IOException.class);
        setupPackageStore();

        Kernel kernelSpy = spy(kernel.parseArgs());
        String deploymentId = "deployment1";
        KernelAlternatives kernelAltsSpy = spy(kernelSpy.getContext().get(KernelAlternatives.class));
        kernelSpy.getContext().put(KernelAlternatives.class, kernelAltsSpy);
        // In actual workflow, DeploymentService will setup deployment artifacts directory per deployment before
        // submitting task. Here in test, it's called explicitly because the directory is required by snapshot file.
        kernelSpy.getContext().get(DeploymentDirectoryManager.class).createNewDeploymentDirectoryIfNotExists(deploymentId);
        kernelSpy.getContext().put(KernelUpdateActivator.class, new KernelUpdateActivator(kernelSpy,
                kernelSpy.getContext().get(BootstrapManager.class)));

        // launch kernel
        kernelSpy.launch();

        // Ensure that the dependency isn't somehow in our class loader already
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.aws.iot.evergreen.integrationtests.kernel" + ".resource.PluginDependency"));

        // First deployment to add plugin-1.0.0 to kernel
        submitSampleJobDocument(getPluginDeploymentDocument(System.currentTimeMillis(), "1.0.0", deploymentId),
                kernelSpy).get(30, TimeUnit.SECONDS);

        EvergreenService eg = kernelSpy.locate(componentName);
        assertEquals("com.aws.iot.evergreen.integrationtests.kernel.resource.APluginService", eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernelSpy.getContext().get(EZPlugins.class)
                .forName("com.aws.iot.evergreen.integrationtests.kernel.resource.PluginDependency");

        String deploymentId2 = "deployment2";
        // No need to actually verify directory setup or make directory changes here.
        doReturn(true).when(kernelAltsSpy).isLaunchDirSetup();
        doNothing().when(kernelAltsSpy).prepareBootstrap(eq(deploymentId2));

        doNothing().when(kernelSpy).shutdown(anyInt(), eq(REQUEST_RESTART));
        // Second deployment to add plugin-1.1.0 to kernel which should enter kernel restart workflow
        assertThrows(TimeoutException.class, () -> submitSampleJobDocument(getPluginDeploymentDocument(
                System.currentTimeMillis(), "1.1.0", deploymentId2), kernelSpy)
                .get(10, TimeUnit.SECONDS));
        verify(kernelSpy).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    private void setupPackageStore() throws IOException, PackagingException, URISyntaxException {
        Path localStoreContentPath = Paths.get(getClass().getResource("local_store_content").toURI());
        Path e2eTestPkgStoreDir = tempRootDir.resolve("eteTestPkgStore");
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());
        PackageStore e2eTestPackageStore = new PackageStore(e2eTestPkgStoreDir);

        componentId = new PackageIdentifier(componentName, new Semver("1.0.0"));
        Path jarFilePath = e2eTestPackageStore.resolveArtifactDirectoryPath(componentId).resolve("plugin-tests.jar");
        // Copy over the same jar file as the plugin-1.1.0 artifact
        FileUtils.copyFile(jarFilePath.toFile(), e2eTestPackageStore.resolveArtifactDirectoryPath(
                new PackageIdentifier(componentName, new Semver("1.1.0")))
                .resolve(componentName + JAR_FILE_EXTENSION).toFile());
        // Rename artifact for plugin-1.0.0
        Files.move(jarFilePath, e2eTestPackageStore.resolveArtifactDirectoryPath(componentId)
                .resolve(componentName + JAR_FILE_EXTENSION));
        kernel.getContext().put(PackageStore.class, e2eTestPackageStore);
    }

    private DeploymentDocument getPluginDeploymentDocument(Long timestamp, String version, String deploymentId) {
        return DeploymentDocument.builder().timestamp(timestamp).deploymentId(deploymentId)
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).rootPackages(Arrays.asList(componentName))
                .componentUpdatePolicy(new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS))
                .groupName("ANY").deploymentPackageConfigurationList(Arrays.asList(
                        new DeploymentPackageConfiguration(componentName, true, version, null))).build();
    }

    private static Future<DeploymentResult> submitSampleJobDocument(DeploymentDocument sampleJobDocument, Kernel kernel) {
        PackageManager packageManager = kernel.getContext().get(PackageManager.class);
        DependencyResolver dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        KernelConfigResolver kernelConfigResolver = kernel.getContext().get(KernelConfigResolver.class);
        DeploymentConfigMerger deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DefaultDeploymentTask deploymentTask =
                new DefaultDeploymentTask(dependencyResolver, packageManager, kernelConfigResolver,
                        deploymentConfigMerger, LogManager.getLogger("Deployer"),
                        new Deployment(sampleJobDocument, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT),
                        Topics.of(kernel.getContext(), DeploymentService.DEPLOYMENT_SERVICE_TOPICS, null));
        return kernel.getContext().get(ExecutorService.class).submit(deploymentTask);
    }
}
