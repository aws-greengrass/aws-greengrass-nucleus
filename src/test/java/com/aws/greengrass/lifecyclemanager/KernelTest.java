/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.lifecyclemanager.Kernel.DEFAULT_CONFIG_YAML_FILE_WRITE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class KernelTest {
    private static final String EXPECTED_CONFIG_OUTPUT =
            "  main:\n"
                    + "    dependencies:\n"
                    + "    - \"" + DEFAULT_NUCLEUS_COMPONENT_NAME + "\"\n"
                    + "    - \"service1\"\n"
                    + "    lifecycle: {}\n"
                    + "  service1:\n"
                    + "    dependencies: []\n"
                    + "    lifecycle:\n"
                    + "      run:\n"
                    + "        script: \"test script\"";

    @TempDir
    protected Path tempRootDir;
    private Kernel kernel;
    private Path mockFile;
    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() throws Exception{
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();

        deviceConfiguration = spy(new DeviceConfiguration(kernel));
        lenient().doNothing().when(deviceConfiguration).initializeNucleusFromRecipe(any());
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);

        mockFile = tempRootDir.resolve("mockFile");
        Files.createFile(mockFile);
    }

    @AfterEach
    void afterEach() throws IOException {
        kernel.shutdown();
        // Some tests use a faked kernel lifecycle, so the shutdown doesn't actually shut it down
        kernel.getContext().close();
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_THEN_dependencies_are_returned_in_order()
            throws Exception {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, new KernelCommandLine(kernel), mock(
                NucleusPaths.class)));
        doNothing().when(kernelLifecycle).initConfigAndTlog();
        kernel.setKernelLifecycle(kernelLifecycle);
        kernel.parseArgs();

        GreengrassService mockMain = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        GreengrassService service1 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();
        GreengrassService service2 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service2.postInject();
        GreengrassService nucleus = kernel.locate(DEFAULT_NUCLEUS_COMPONENT_NAME);

        List<GreengrassService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(2));
        assertEquals(mockMain, od.get(1));

        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(3));

        assertThat(od.get(0), anyOf(is(service1), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(nucleus)));
        assertEquals(mockMain, od.get(2));

        mockMain.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(4));

        // Since service 1, 2 and Nucleus are equal in the tree, they may come back as either position 1, 2 or 3
        assertThat(od.get(0), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(2), anyOf(is(service1), is(service2), is(nucleus)));
        assertEquals(mockMain, od.get(3));

        service1.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(4));

        // Now that 2 is a dependency of 1, 2 has to be ordered before 1
        // Possible orders are -> [service2, service1, nucleus, main]; [nucleus, service2, service1, main]
        // and [service2, nucleus, service1, main]
        assertThat(od.get(0), anyOf(is(service2), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(2), anyOf(is(service1), is(nucleus)));
        assertEquals(mockMain, od.get(3));
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_with_a_cycle_THEN_no_dependencies_returned()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class),
                mock(NucleusPaths.class)));
        doNothing().when(kernelLifecycle).initConfigAndTlog();
        kernel.setKernelLifecycle(kernelLifecycle);
        kernel.parseArgs();

        GreengrassService mockMain =
                new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        GreengrassService service1 =
                new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        // Introduce a dependency cycle
        service1.addOrUpdateDependency(mockMain, DependencyType.HARD, true);
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, true);

        // Nucleus component is always present as an additional dependency of main
        List<GreengrassService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(1));
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_writeConfig_THEN_service_config_written_to_file()
            throws Exception {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class),
                kernel.getNucleusPaths()));
        kernel.setKernelLifecycle(kernelLifecycle);

        GreengrassService mockMain = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        GreengrassService service1 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        kernel.parseArgs();

        // Add dependency on service1 to main
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);
        ((List<String>) kernel.findServiceTopic("main").findLeafChild(
                GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC).getOnce())
                .add("service1");
        kernel.findServiceTopic("service1").lookup(GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run", "script")
                .withValue("test script");

        StringWriter writer = new StringWriter();
        kernel.writeConfig(writer);
        assertThat(writer.toString(), containsString(EXPECTED_CONFIG_OUTPUT));

        kernel.writeEffectiveConfig();
        String readFile =
                new String(Files.readAllBytes(kernel.getNucleusPaths().configPath()
                        .resolve(DEFAULT_CONFIG_YAML_FILE_WRITE)),
                StandardCharsets.UTF_8);
        assertThat(readFile, containsString(EXPECTED_CONFIG_OUTPUT));
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_definition_in_config_THEN_create_GenericExternalService()
            throws Exception {
        Configuration config = kernel.getConfig();
        config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "1",
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

        GreengrassService main = kernel.locate("1");
        assertEquals("1", main.getName());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_WHEN_locate_finds_class_definition_in_config_THEN_create_service(ExtensionContext context)
            throws Exception {
        // We need to launch the kernel here as this triggers EZPlugins to search the classpath for @ImplementsService
        // it complains that there's no main, but we don't care for this test
        ignoreExceptionUltimateCauseWithMessage(context, "No matching definition in system model for: main");
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }
        Configuration config = kernel.getConfig();
        config.lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC, "1", "class")
                .withValue(TestClass.class.getName());

        GreengrassService main = kernel.locate("1");
        assertEquals("tester", main.getName());

        kernel.getContext().get(EZPlugins.class).scanSelfClasspath();
        GreengrassService service2 = kernel.locate("testImpl");
        assertEquals("testImpl", service2.getName());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_with_disk_spooler_config_WHEN_locate_spooler_impl_THEN_create_test_spooler_service()
            throws Exception {
        try {
            System.setProperty("aws.greengrass.scanSelfClasspath", "true");
            try {
                kernel.parseArgs("-i", getClass().getResource("spooler_config.yaml").toString()).launch();
            } catch (RuntimeException ignored) {
            }
            GreengrassService service = kernel.locate("testSpooler");
            assertEquals("testSpooler", service.getName());
            assertTrue(service instanceof CloudMessageSpool);
        } finally {
            System.setProperty("aws.greengrass.scanSelfClasspath", "false");
        }
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_classname_but_not_class_THEN_throws_ServiceLoadException() {
        String badClassName = TestClass.class.getName()+"lkajsdklajglsdj";

        Configuration config = kernel.getConfig();
        config.lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC, "2", "class")
                .withValue(badClassName);

        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("2"));
        assertEquals("Can't load service class from " + badClassName, ex.getMessage());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_no_definition_in_config_THEN_throws_ServiceLoadException() {
        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("5"));
        assertEquals("No matching definition in system model for: 5", ex.getMessage());
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_get_root_package_with_version_THEN_kernel_returns_info() {

        GreengrassService service1 = new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        GreengrassService service2 = new GreengrassService(kernel.getConfig()
                .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service1.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.0.0");
        service2.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.1.0");

        GreengrassService mockMain = mock(GreengrassService.class);
        Map<GreengrassService, DependencyType> mainsDependency = new HashMap<>();
        mainsDependency.put(service1, null);
        mainsDependency.put(service2, null);
        when(mockMain.getDependencies()).thenReturn(mainsDependency);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        kernel.setKernelLifecycle(kernelLifecycle);

        Map<String, String> rootPackageNameAndVersion = kernel.getRunningCustomRootComponents();
        assertEquals(2, rootPackageNameAndVersion.size());
        assertEquals("1.0.0", rootPackageNameAndVersion.get("service1"));
        assertEquals("1.1.0", rootPackageNameAndVersion.get("service2"));

    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_deployment_activation_happy_path_THEN_inject_deployment()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).launch();
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ACTIVATION).when(kernelAlternatives).determineDeploymentStage(any(), any());
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Deployment.class)).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertThat(deployments.toArray(), hasSize(1));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_deployment_rollback_cannot_reload_deployment_THEN_proceed_as_default(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        doNothing().when(kernelLifecycle).launch();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ROLLBACK).when(kernelAlternatives).determineDeploymentStage(any(), any());
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doThrow(new IOException()).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertNull(deployments.poll());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_finishes_THEN_prepare_restart_into_activation()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doReturn(NO_OP).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(false).when(bootstrapManager).hasNext();
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_requires_reboot_THEN_prepare_reboot()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doReturn(REQUEST_REBOOT).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(true).when(bootstrapManager).hasNext();
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_REBOOT));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_fails_THEN_prepare_rollback(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        ServiceUpdateException mockSUE = new ServiceUpdateException("mock error", DeploymentErrorCode.COMPONENT_BOOTSTRAP_ERROR,
                DeploymentErrorType.USER_COMPONENT_ERROR);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doThrow(mockSUE).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelAlternatives).prepareRollback();
        verify(deployment).setErrorStack(eq(Arrays.asList("DEPLOYMENT_FAILURE", "COMPONENT_UPDATE_ERROR",
                "COMPONENT_BOOTSTRAP_ERROR")));
        verify(deployment).setErrorTypes(eq(Collections.singletonList("USER_COMPONENT_ERROR")));
        verify(deploymentDirectoryManager).writeDeploymentMetadata(eq(deployment));
        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_fails_and_prepare_rollback_fails_THEN_continue(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doThrow(new IOException()).when(kernelAlternatives).prepareRollback();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
        doNothing().when(deploymentDirectoryManager).writeDeploymentMetadata(any());
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doThrow(new ServiceUpdateException("mock error")).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelAlternatives).prepareRollback();
        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    static class TestClass extends GreengrassService {
        public TestClass(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "tester";
        }
    }

    @ImplementsService(name = "testImpl")
    static class TestImplementor extends GreengrassService {
        public TestImplementor(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testImpl";
        }
    }

    @ImplementsService(name = "testSpooler")
    static class TestSpooler extends PluginService implements CloudMessageSpool {
        public TestSpooler(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testSpooler";
        }

        @Override
        public SpoolMessage getMessageById(long id) {
            return null;
        }

        @Override
        public void removeMessageById(long id) {

        }

        @Override
        public void add(long id, SpoolMessage message) throws IOException {

        }

        @Override
        public Iterable<Long> getAllMessageIds() throws IOException {
            return null;
        }

        @Override
        public void initializeSpooler() throws IOException {

        }
    }
}
