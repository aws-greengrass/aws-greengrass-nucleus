/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.DefaultDeploymentTask;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.activator.KernelUpdateActivator;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
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

import static com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction.NOTIFY_COMPONENTS;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.dependency.EZPlugins.JAR_FILE_EXTENSION;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class PluginComponentTest extends BaseITCase {
    private static final String componentName = "plugin";
    private Kernel kernel;
    private final ComponentIdentifier componentId = new ComponentIdentifier(componentName, new Semver("1.0.0"));

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

        GreengrassService eg = kernel.locate(componentName);
        assertEquals("com.aws.greengrass.integrationtests.lifecyclemanager.resource.APluginService",
                eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernel.getContext().get(EZPlugins.class)
                .forName("com.aws.greengrass.integrationtests.lifecyclemanager.resource.PluginDependency");
        assertFalse(eg.isBuiltin());
    }

    @Test
    void GIVEN_kernel_with_plugin_WHEN_locate_plugin_THEN_plugin_is_loaded_into_JVM_and_is_builtin() throws Exception {
        Path localStoreContentPath = Paths.get(getClass().getResource("local_store_content").toURI());
        Path trustedPluginDirectory = tempRootDir.resolve("plugins").resolve("trusted");
        FileUtils.copyDirectory(localStoreContentPath.toAbsolutePath().resolve("artifacts").resolve(componentName)
                .resolve(componentId.getVersion().toString()).toFile(), trustedPluginDirectory.toFile());

        kernel.parseArgs().launch();

        GreengrassService eg = kernel.locate(componentName);
        assertEquals("com.aws.greengrass.integrationtests.lifecyclemanager.resource.APluginService",
                eg.getClass().getName());
        assertTrue(eg.isBuiltin());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_plugin_dependency_THEN_dependency_from_plugin_is_loaded_into_JVM() throws Exception {
        setupPackageStore();
        kernel.parseArgs("-i", this.getClass().getResource("plugin_dependency.yaml").toString());
        kernel.launch();

        GreengrassService eg = kernel.locate("plugin-dependency");
        assertEquals("com.aws.greengrass.integrationtests.lifecyclemanager.resource.PluginDependencyService",
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
                () -> Class.forName("com.aws.greengrass.integrationtests.kernel" + ".resource.PluginDependency"));

        submitSampleJobDocument(getPluginDeploymentDocument(System.currentTimeMillis(), "1.0.0",
                "f7fe5b16-574a-11ea-82b4-0242ac130004"), kernel).get(30, TimeUnit.SECONDS);

        GreengrassService eg = kernel.locate(componentName);
        assertEquals("com.aws.greengrass.integrationtests.lifecyclemanager.resource.APluginService",
                eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernel.getContext().get(EZPlugins.class)
                .forName("com.aws.greengrass.integrationtests.lifecyclemanager.resource.PluginDependency");
    }

    @Test
    void GIVEN_kernel_WHEN_deploy_updated_plugin_THEN_request_kernel_restart(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, IOException.class);
        setupPackageStore();

        Kernel kernelSpy = spy(kernel.parseArgs());
        String deploymentId = "deployment1";
        KernelAlternatives kernelAltsSpy = spy(kernelSpy.getContext().get(KernelAlternatives.class));
        kernelSpy.getContext().put(KernelAlternatives.class, kernelAltsSpy);
        // In actual workflow, DeploymentService will setup deployment artifacts directory per deployment before
        // submitting task. Here in test, it's called explicitly because the directory is required by snapshot file.
        kernelSpy.getContext().get(DeploymentDirectoryManager.class)
                .createNewDeploymentDirectoryIfNotExists(deploymentId);
        kernelSpy.getContext().put(KernelUpdateActivator.class,
                new KernelUpdateActivator(kernelSpy, kernelSpy.getContext().get(BootstrapManager.class)));

        // launch kernel
        kernelSpy.launch();

        // Ensure that the dependency isn't somehow in our class loader already
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.aws.greengrass.integrationtests.lifecyclemanager.resource.PluginDependency"));

        // First deployment to add plugin-1.0.0 to kernel
        submitSampleJobDocument(getPluginDeploymentDocument(System.currentTimeMillis(), "1.0.0", deploymentId),
                kernelSpy).get(30, TimeUnit.SECONDS);

        GreengrassService eg = kernelSpy.locate(componentName);
        assertEquals("com.aws.greengrass.integrationtests.lifecyclemanager.resource.APluginService",
                eg.getClass().getName());
        assertEquals(componentId.getVersion().toString(),
                Coerce.toString(eg.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY)));
        kernelSpy.getContext().get(EZPlugins.class)
                .forName("com.aws.greengrass.integrationtests.lifecyclemanager.resource.PluginDependency");

        String deploymentId2 = "deployment2";
        // No need to actually verify directory setup or make directory changes here.
        doReturn(true).when(kernelAltsSpy).isLaunchDirSetup();
        doNothing().when(kernelAltsSpy).prepareBootstrap(eq(deploymentId2));

        doNothing().when(kernelSpy).shutdown(anyInt(), eq(REQUEST_RESTART));
        // Second deployment to add plugin-1.1.0 to kernel which should enter kernel restart workflow
        assertThrows(TimeoutException.class, () -> submitSampleJobDocument(
                getPluginDeploymentDocument(System.currentTimeMillis(), "1.1.0", deploymentId2), kernelSpy)
                .get(10, TimeUnit.SECONDS));
        verify(kernelSpy).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    private void setupPackageStore() throws IOException, PackagingException, URISyntaxException {
        Path localStoreContentPath = Paths.get(getClass().getResource("local_store_content").toURI());
        Path e2eTestPkgStoreDir = tempRootDir.resolve("eteTestPkgStore");
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());
        ComponentStore e2ETestComponentStore = new ComponentStore(e2eTestPkgStoreDir);
        Path jarFilePath = e2ETestComponentStore.resolveArtifactDirectoryPath(componentId).resolve("plugin-tests.jar");
        // Copy over the same jar file as the plugin-1.1.0 artifact
        FileUtils.copyFile(jarFilePath.toFile(), e2ETestComponentStore
                .resolveArtifactDirectoryPath(new ComponentIdentifier(componentName, new Semver("1.1.0")))
                .resolve(componentName + JAR_FILE_EXTENSION).toFile());
        // Rename artifact for plugin-1.0.0
        Files.move(jarFilePath, e2ETestComponentStore.resolveArtifactDirectoryPath(componentId)
                .resolve(componentName + JAR_FILE_EXTENSION));
        kernel.getContext().put(ComponentStore.class, e2ETestComponentStore);
    }

    private DeploymentDocument getPluginDeploymentDocument(Long timestamp, String version, String deploymentId) {
        return DeploymentDocument.builder().timestamp(timestamp).deploymentId(deploymentId)
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).rootPackages(Arrays.asList(componentName))
                .componentUpdatePolicy(new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS)).groupName("ANY")
                .deploymentPackageConfigurationList(
                        Arrays.asList(new DeploymentPackageConfiguration(componentName, true, version, null))).build();
    }

    private static Future<DeploymentResult> submitSampleJobDocument(DeploymentDocument sampleJobDocument,
                                                                    Kernel kernel) {
        ComponentManager componentManager = kernel.getContext().get(ComponentManager.class);
        DependencyResolver dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        KernelConfigResolver kernelConfigResolver = kernel.getContext().get(KernelConfigResolver.class);
        DeploymentConfigMerger deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DefaultDeploymentTask deploymentTask =
                new DefaultDeploymentTask(dependencyResolver, componentManager, kernelConfigResolver,
                        deploymentConfigMerger, LogManager.getLogger("Deployer"),
                        new Deployment(sampleJobDocument, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT),
                        Topics.of(kernel.getContext(), DeploymentService.DEPLOYMENT_SERVICE_TOPICS, null));
        return kernel.getContext().get(ExecutorService.class).submit(deploymentTask);
    }
}
