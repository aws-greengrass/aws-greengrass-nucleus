/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeploymentDirectoryManager;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENTS_QUEUE;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class KernelTest {
    private static final String EXPECTED_CONFIG_OUTPUT =
            "services:\n"
            + "  service1:\n"
            + "    dependencies: []\n"
            + "    lifecycle:\n"
            + "      run:\n"
            + "        script: \"test script\"\n"
            + "  main:\n"
            + "    dependencies:\n"
            + "    - \"service1\"\n";

    @TempDir
    protected Path tempRootDir;
    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_THEN_dependencies_are_returned_in_order()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, new KernelCommandLine(kernel)));
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain = new EvergreenService(
                kernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        EvergreenService service1 = new EvergreenService(
                kernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();
        EvergreenService service2 = new EvergreenService(
                kernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service2.postInject();

        List<EvergreenService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(1));
        assertEquals(mockMain, od.get(0));

        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(2));

        assertEquals(service1, od.get(0));
        assertEquals(mockMain, od.get(1));

        mockMain.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(3));

        // Since service 1 and 2 are equal in the tree, they may come back as either position 1 or 2
        assertThat(od.get(0), anyOf(is(service1), is(service2)));
        assertThat(od.get(1), anyOf(is(service1), is(service2)));
        assertEquals(mockMain, od.get(2));

        service1.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(3));

        // Now that 2 is a dependency of 1, there is a strict order required
        assertEquals(service2, od.get(0));
        assertEquals(service1, od.get(1));
        assertEquals(mockMain, od.get(2));
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_with_a_cycle_THEN_no_dependencies_returned()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class)));
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        EvergreenService service1 =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        // Introduce a dependency cycle
        service1.addOrUpdateDependency(mockMain, DependencyType.HARD, false);
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);

        List<EvergreenService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(0));
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_writeConfig_THEN_service_config_written_to_file()
            throws Exception {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class)));
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain = new EvergreenService(
                kernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        EvergreenService service1 = new EvergreenService(
                kernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        kernel.parseArgs();

        // Add dependency on service1 to main
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);
        ((List<String>) kernel.findServiceTopic("main").findLeafChild(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC).getOnce())
                .add("service1");
        kernel.findServiceTopic("service1").lookup(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run", "script")
                .withValue("test script");

        StringWriter writer = new StringWriter();
        kernel.writeConfig(writer);
        assertThat(writer.toString(), containsString(EXPECTED_CONFIG_OUTPUT));

        kernel.writeEffectiveConfig();
        String readFile = new String(Files.readAllBytes(kernel.getConfigPath().resolve("effectiveConfig.evg")),
                StandardCharsets.UTF_8);
        assertThat(readFile, containsString(EXPECTED_CONFIG_OUTPUT));
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_definition_in_config_THEN_create_GenericExternalService()
            throws Exception {
        Configuration config = kernel.getConfig();
        config.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "1",
                EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

        EvergreenService main = kernel.locate("1");
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
        config.lookup(EvergreenService.SERVICES_NAMESPACE_TOPIC, "1", "class")
                .withValue(TestClass.class.getName());

        EvergreenService main = kernel.locate("1");
        assertEquals("tester", main.getName());

        EvergreenService service2 = kernel.locate("testImpl");
        assertEquals("testImpl", service2.getName());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_classname_but_not_class_THEN_throws_ServiceLoadException() {
        String badClassName = TestClass.class.getName()+"lkajsdklajglsdj";

        Configuration config = kernel.getConfig();
        config.lookup(EvergreenService.SERVICES_NAMESPACE_TOPIC, "2", "class")
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

        EvergreenService service1 = new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));
        EvergreenService service2 = new EvergreenService(kernel.getConfig()
                .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service1.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.0.0");
        service2.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.1.0");

        EvergreenService mockMain = mock(EvergreenService.class);
        Map<EvergreenService, DependencyType> mainsDependency = new HashMap<>();
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
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ACTIVATION).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Deployment.class)).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        LinkedBlockingQueue<Deployment> deployments = (LinkedBlockingQueue<Deployment>)
                kernel.getContext().getvIfExists(DEPLOYMENTS_QUEUE).get();
        assertEquals(1, deployments.size());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_deployment_rollback_cannot_reload_deployment_THEN_proceed_as_default(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).launch();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ROLLBACK).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doThrow(new IOException()).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        LinkedBlockingQueue<Deployment> deployments = (LinkedBlockingQueue<Deployment>)
                kernel.getContext().getvIfExists(DEPLOYMENTS_QUEUE).get();
        assertEquals(0, deployments.size());
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
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
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
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
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
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        Deployment deployment = mock(Deployment.class);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
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
        verify(deploymentDirectoryManager).writeDeploymentMetadata(eq(deployment));
        verify(kernelLifecycle).shutdown(eq(30), eq(2));
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
        doReturn(kernelAlternatives).when(kernelCommandLine).getKernelAlternatives();

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
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
        verify(deploymentDirectoryManager, times(0)).writeDeploymentMetadata(any());
        verify(kernelLifecycle).shutdown(eq(30), eq(2));
    }

    static class TestClass extends EvergreenService {
        public TestClass(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "tester";
        }
    }

    @ImplementsService(name = "testImpl")
    static class TestImplementor extends EvergreenService {
        public TestImplementor(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testImpl";
        }
    }
}
