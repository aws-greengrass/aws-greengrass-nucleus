/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
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
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.config.Topic.DEFAULT_VALUE_TIMESTAMP;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;
import static com.aws.greengrass.lifecyclemanager.Kernel.DEFAULT_CONFIG_YAML_FILE_WRITE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
// This test class was already at PMD's object-coupling and class-length limits before the
// interrupted-deployment cases were added. Splitting it up is worth doing on its own, not as part of a
// bug fix.
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveClassLength"})
class KernelTest {
    private static final String UNFINISHED_DEPLOYMENT_ID =
            "arn:aws:greengrass:us-east-1:123456789012:configuration:thing/UnfinishedThing:7";
    // A rollback snapshot as the nucleus writes one, a transaction log.
    private static final String VALID_SNAPSHOT =
            "{\"TS\":1602000000000,\"TP\":[\"services\",\"com.example.Component\",\"version\"],"
                    + "\"W\":\"changed\",\"V\":\"1.0.0\"}\n";
    // config.tlog as an interrupted deployment left it, and as it is once the device has run on after a restore.
    private static final String UNVERIFIED_CONFIG =
            "{\"TS\":1602000000001,\"TP\":[\"services\",\"com.example.Component\",\"version\"],"
                    + "\"W\":\"changed\",\"V\":\"2.0.0\"}\n";
    private static final String CHANGED_SINCE_RESTORE =
            "{\"TS\":1602000000002,\"TP\":[\"services\",\"com.example.Component\",\"version\"],"
                    + "\"W\":\"changed\",\"V\":\"1.0.0\"}\n";
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
    private Path tempFile;
    DeviceConfiguration deviceConfiguration;
    @Mock
    private GreengrassService service1;
    @Mock
    private GreengrassService service2;
    @Mock
    private GreengrassService service3;
    @Mock
    private GreengrassService service4;
    @BeforeEach
    void beforeEach() throws Exception{
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();

        deviceConfiguration = spy(new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine()));
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);

        mockFile = tempRootDir.resolve("mockFile");
        Files.createFile(mockFile);

        tempFile = tempRootDir.resolve("testFile");
        Files.createFile(tempFile);
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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

    @Test
    void GIVEN_kernel_launch_WHEN_deployment_was_interrupted_without_completing_THEN_reprocess_it()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(true).when(deploymentDirectoryManager).hasReadableDeploymentMetadata();
        doReturn(new Deployment("{}", Deployment.DeploymentType.LOCAL, "mockId"))
                .when(deploymentDirectoryManager).readDeploymentMetadata();

        launchWithDefaultStage(deploymentDirectoryManager);

        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(1));
    }

    @Test
    void GIVEN_kernel_launch_WHEN_interrupted_cloud_deployment_THEN_left_for_the_cloud_to_redeliver()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(true).when(deploymentDirectoryManager).hasReadableDeploymentMetadata();
        doReturn(new Deployment("{}", Deployment.DeploymentType.IOT_JOBS, "mockId"))
                .when(deploymentDirectoryManager).readDeploymentMetadata();

        launchWithDefaultStage(deploymentDirectoryManager);

        // Re-processing it from disk would race the cloud's own re-delivery, which carries the document
        // the cloud holds and can resolve components this device cannot.
        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
    }

    @Test
    void GIVEN_kernel_launch_WHEN_no_deployment_in_progress_THEN_nothing_reprocessed() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(false).when(deploymentDirectoryManager).hasReadableDeploymentMetadata();

        launchWithDefaultStage(deploymentDirectoryManager);

        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
        verify(deploymentDirectoryManager, times(0)).readDeploymentMetadata();
    }

    @Test
    void GIVEN_kernel_launch_WHEN_interrupted_deployment_exhausted_its_attempts_THEN_boot_from_rollback_snapshot()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.LOCAL,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs().launch();

        // config.tlog now holds exactly the snapshot, and the launch boots it like any config.tlog.
        assertEquals(VALID_SNAPSHOT, read(configTlog));
        assertEquals(UNVERIFIED_CONFIG, read(configTlog.resolveSibling("config.tlog~")));
        verify(kernelLifecycle).initConfigAndTlog();
        verify(kernelLifecycle, never()).initConfigAndTlog(anyString());
        // Still re-queued, so DeploymentService reports the deployment failed rather than leaving it
        // outstanding for the cloud to deliver again.
        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(1));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORED, launchRecoveryRecorded());
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_args_parsed_without_launch_THEN_restored_once_and_nothing_requeued()
            throws Exception {
        // GreengrassSetup parses arguments and can exit without launching, e.g. to set up the system service.
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.LOCAL,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        assertEquals(VALID_SNAPSHOT, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        verify(kernelLifecycle, never()).launch();
        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
        // The restore is recorded once it has taken effect, so the launch that follows does not restore again.
        assertNull(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).getRecovery());
    }

    @Test
    void GIVEN_exhausted_deployment_restored_WHEN_args_parsed_again_THEN_snapshot_not_restored_again()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();
        assertEquals(VALID_SNAPSHOT, read(configTlog));
        Files.write(configTlog, CHANGED_SINCE_RESTORE.getBytes(StandardCharsets.UTF_8));
        // Restoring again would erase everything written to config.tlog since the first restore.
        kernel.parseArgs();

        assertEquals(CHANGED_SINCE_RESTORE, read(configTlog));
        verify(kernelLifecycle, times(2)).initConfigAndTlog();
    }

    @Test
    void GIVEN_exhausted_deployment_restored_WHEN_nucleus_restarts_THEN_snapshot_not_restored_again()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        wireDefaultStageLaunch(deploymentDirectoryManager, mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);
        kernel.parseArgs();
        assertEquals(VALID_SNAPSHOT, read(configTlog));
        Files.write(configTlog, CHANGED_SINCE_RESTORE.getBytes(StandardCharsets.UTF_8));

        // A restart before the cloud re-delivers the deployment, on the same root.
        kernel.shutdown();
        kernel.getContext().close();
        kernel = new Kernel();
        kernel.getContext().put(DeviceConfiguration.class,
                spy(new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine())));
        KernelLifecycle secondLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        kernel.parseArgs();

        assertEquals(CHANGED_SINCE_RESTORE, read(configTlog));
        verify(secondLifecycle).initConfigAndTlog();
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_explicit_config_provided_THEN_explicit_config_wins_and_not_restored_later()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW, VALID_SNAPSHOT,
                true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        doReturn(tempFile.toString()).when(kernelCommandLine).getProvidedConfigPathName();
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager, kernelCommandLine);
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        // Decided once: a later launch without the explicit configuration does not override it with the snapshot.
        assertNull(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).getRecovery());
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_init_config_provided_THEN_init_config_wins() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW, VALID_SNAPSHOT,
                true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        doReturn(tempFile.toString()).when(kernelCommandLine).getProvidedInitialConfigPath();
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager, kernelCommandLine);
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        // Decided once, as for -i/--config, so the deployment's failure reports why the device was not restored.
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.EXPLICIT_CONFIGURATION, launchRecoveryRecorded());
    }

    @Test
    void GIVEN_exhausted_deployment_that_does_not_roll_back_WHEN_args_parsed_THEN_not_restored() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW, VALID_SNAPSHOT,
                false, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        // DO_NOTHING: a failure of this deployment would not roll back, so neither does using up its attempts.
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_NOT_REQUESTED, launchRecoveryRecorded());
    }

    @Test
    void GIVEN_exhausted_deployment_with_invalid_snapshot_WHEN_args_parsed_THEN_snapshot_not_applied(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, com.fasterxml.jackson.core.JsonParseException.class);
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW,
                "\u0000\u0000 not a transaction log", true,
                DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        // Reading it would apply only the lines that parse and make that partial configuration durable.
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE, launchRecoveryRecorded());
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_restoring_its_snapshot_fails_THEN_device_boots_its_config_and_failure_recorded(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, java.nio.file.FileSystemException.class);
        // What Windows throws for opening a directory as a file.
        ignoreExceptionOfType(context, java.nio.file.AccessDeniedException.class);
        ignoreExceptionOfType(context, java.nio.file.DirectoryNotEmptyException.class);
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        Path snapshotPath = stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);
        // Writing the copy next to config.tlog fails, as on a full disk, an I/O error or a read-only filesystem.
        // A directory where the copy has to go fails it for any user, root included.
        Files.createDirectories(configTlog.resolveSibling("config.tlog+").resolve("occupied"));

        // With Restart=on-failure a throw here would be a boot loop; main boots config.tlog.
        kernel.parseArgs();

        verify(kernelLifecycle).initConfigAndTlog();
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        // The snapshot is kept, and what happened is recorded for the deployment's failure status.
        assertEquals(VALID_SNAPSHOT, read(snapshotPath));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORE_FAILED, launchRecoveryRecorded());
        // Decided once, so a later launch does not keep retrying a restore before every boot.
        assertNull(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).getRecovery());
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_restore_throws_unexpectedly_THEN_device_boots_its_config(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, IllegalStateException.class);
        DeploymentDirectoryManager deploymentDirectoryManager = spy(deploymentDirectoryManagerOnDisk());
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW,
                VALID_SNAPSHOT, true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);
        doThrow(new IllegalStateException("unexpected")).when(deploymentDirectoryManager)
                .restoreRollbackSnapshot(any(), any());

        kernel.parseArgs();

        verify(kernelLifecycle).initConfigAndTlog();
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORE_FAILED, launchRecoveryRecorded());
    }

    @Test
    // Windows moves a directory over a file, so the leftover below is only unmovable on POSIX. The launch's handling
    // of a failed restore is covered on every OS by the restore-failure tests above; the settle failure itself by
    // DeploymentDirectoryManagerTest.
    @DisabledOnOs(OS.WINDOWS)
    void GIVEN_exhausted_deployment_WHEN_truncation_leftover_cannot_be_settled_THEN_not_restored_and_failure_recorded(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, java.nio.file.FileSystemException.class);
        ignoreExceptionOfType(context, java.nio.file.DirectoryNotEmptyException.class);
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.SHADOW, VALID_SNAPSHOT, true,
                DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);
        // An interrupted truncation's leftover that cannot be removed or moved, for any user. The launch moves it
        // back over config.tlog, so a restore it survived would be undone while recorded as done.
        Files.createDirectories(configTlog.resolveSibling("config.tlog.old").resolve("occupied"));

        kernel.parseArgs();

        verify(kernelLifecycle).initConfigAndTlog();
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORE_FAILED, launchRecoveryRecorded());
    }

    @Test
    void GIVEN_cancelled_local_deployment_on_ongoing_link_WHEN_launched_THEN_not_reprocessed() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.LOCAL, VALID_SNAPSHOT, true,
                1);
        // CANCELED was published for it, and DeploymentService recorded the cancel first.
        recordTerminalOutcomeOnDisk("CANCELLED");
        wireDefaultStageLaunch(deploymentDirectoryManager, mock(KernelCommandLine.class));

        kernel.parseArgs().launch();

        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
        // It stays on the ongoing link, for the next deployment to carry its snapshot forward.
        assertTrue(deploymentDirectoryManager.hasUnfinishedDeployment());
    }

    @Test
    void GIVEN_cancelled_deployment_that_used_up_its_attempts_WHEN_args_parsed_THEN_not_restored() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.IOT_JOBS, VALID_SNAPSHOT,
                true, DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        // Its job was cancelled during the last allowed attempt.
        recordTerminalOutcomeOnDisk("CANCELLED");
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));
        Path configTlog = givenConfigTlog(UNVERIFIED_CONFIG);

        kernel.parseArgs();

        // Cancelling is not failing: nothing restores the device behind the back of whoever cancelled it.
        assertEquals(UNVERIFIED_CONFIG, read(configTlog));
        verify(kernelLifecycle).initConfigAndTlog();
        assertNull(launchRecoveryRecorded());
    }

    @Test
    void GIVEN_interrupted_cloud_deployment_WHEN_launched_THEN_log_says_awaiting_redelivery_not_reprocessing()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.IOT_JOBS, VALID_SNAPSHOT,
                true, 1);
        wireDefaultStageLaunch(deploymentDirectoryManager, mock(KernelCommandLine.class));

        List<String> messages = logMessagesDuring(() -> kernel.parseArgs().launch());

        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
        assertFalse(messages.stream().anyMatch(m -> m.contains("re-process") && !m.contains("Not re-processing")),
                messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("awaiting the cloud to re-deliver it")),
                messages::toString);
    }

    @Test
    void GIVEN_interrupted_local_deployment_WHEN_launched_THEN_log_says_reprocessing() throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.LOCAL, VALID_SNAPSHOT, true,
                1);
        wireDefaultStageLaunch(deploymentDirectoryManager, mock(KernelCommandLine.class));

        List<String> messages = logMessagesDuring(() -> kernel.parseArgs().launch());

        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(1));
        assertTrue(messages.stream().anyMatch(m -> m.contains("interrupted before it completed. Re-processing it")),
                messages::toString);
    }

    @Test
    void GIVEN_deployment_finished_but_not_moved_off_ongoing_link_WHEN_launched_THEN_not_treated_as_unfinished()
            throws Exception {
        DeploymentDirectoryManager deploymentDirectoryManager = deploymentDirectoryManagerOnDisk();
        stageUnfinishedDeployment(deploymentDirectoryManager, Deployment.DeploymentType.LOCAL, VALID_SNAPSHOT, true,
                DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        // Its terminal status was recorded, then the nucleus stopped before the directory moved.
        deploymentDirectoryManager.recordTerminalStatus(UNFINISHED_DEPLOYMENT_ID,
                DeploymentDirectoryManager.TerminalOutcome.SUCCEEDED);
        KernelLifecycle kernelLifecycle = wireDefaultStageLaunch(deploymentDirectoryManager,
                mock(KernelCommandLine.class));

        kernel.parseArgs().launch();

        verify(kernelLifecycle, never()).initConfigAndTlog(anyString());
        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
    }

    @Test
    void GIVEN_kernel_launch_WHEN_interrupted_deployment_cannot_be_read_THEN_launch_still_proceeds(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(true).when(deploymentDirectoryManager).hasReadableDeploymentMetadata();
        doThrow(new RuntimeException("corrupt metadata")).when(deploymentDirectoryManager).readDeploymentMetadata();

        KernelLifecycle kernelLifecycle = launchWithDefaultStage(deploymentDirectoryManager, false);

        // Re-processing is a recovery step: failing it must not keep the device from starting.
        verify(kernelLifecycle).launch();
        assertThat(kernel.getContext().get(DeploymentQueue.class).toArray(), hasSize(0));
    }

    private void launchWithDefaultStage(DeploymentDirectoryManager deploymentDirectoryManager) throws Exception {
        launchWithDefaultStage(deploymentDirectoryManager, false);
    }

    private KernelLifecycle launchWithDefaultStage(DeploymentDirectoryManager deploymentDirectoryManager,
                                                  boolean expectConfigOverride) throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).launch();
        if (expectConfigOverride) {
            doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        } else {
            doNothing().when(kernelLifecycle).initConfigAndTlog();
        }
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(DEFAULT).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();
        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();
        kernel.setKernelCommandLine(kernelCommandLine);

        kernel.parseArgs().launch();
        return kernelLifecycle;
    }

    private DeploymentDirectoryManager deploymentDirectoryManagerOnDisk() throws IOException {
        Path deployments = Files.createDirectories(tempRootDir.resolve("deployments"));
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        doReturn(deployments).when(nucleusPaths).deploymentPath();
        return new DeploymentDirectoryManager(kernel, nucleusPaths);
    }

    /**
     * Leave a deployment on disk the way an interrupted one is left: its directory on the ongoing link, its
     * metadata, its rollback snapshot and the given number of recorded attempts.
     */
    private static Path stageUnfinishedDeployment(DeploymentDirectoryManager deploymentDirectoryManager,
                                                  Deployment.DeploymentType type, String snapshot,
                                                  boolean rollbackOnFailure, int attempts) throws IOException {
        Path dir = deploymentDirectoryManager.createNewDeploymentDirectory(UNFINISHED_DEPLOYMENT_ID);
        deploymentDirectoryManager.writeDeploymentMetadata(new Deployment("{}", type, UNFINISHED_DEPLOYMENT_ID));
        Path snapshotPath = dir.resolve("rollback_snapshot.tlog");
        Files.write(snapshotPath, snapshot.getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < attempts; attempt++) {
            deploymentDirectoryManager.recordProcessingAttempt(UNFINISHED_DEPLOYMENT_ID,
                    DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, rollbackOnFailure);
        }
        return snapshotPath;
    }

    private KernelLifecycle wireDefaultStageLaunch(DeploymentDirectoryManager deploymentDirectoryManager,
                                                   KernelCommandLine kernelCommandLine) throws IOException {
        // The launch restores config.tlog in the configuration directory, which parsing the command line sets up.
        kernel.getNucleusPaths().setConfigPath(tempRootDir.resolve("config"));
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        lenient().doNothing().when(kernelLifecycle).launch();
        lenient().doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        lenient().doNothing().when(kernelLifecycle).initConfigAndTlog();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(DEFAULT).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();
        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();
        kernel.setKernelCommandLine(kernelCommandLine);
        return kernelLifecycle;
    }

    /**
     * Write config.tlog as the launch finds it.
     */
    private Path givenConfigTlog(String content) throws IOException {
        Path configTlog = tempRootDir.resolve("config").resolve("config.tlog");
        Files.write(configTlog, content.getBytes(StandardCharsets.UTF_8));
        return configTlog;
    }

    /**
     * Set the terminal outcome in the staged deployment's record, as the nucleus that recorded it wrote it.
     */
    private void recordTerminalOutcomeOnDisk(String outcome) throws IOException {
        Path record = processingStateFileOfStagedDeployment();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> state = mapper.readValue(record.toFile(), Map.class);
        state.put("terminalOutcome", outcome);
        Files.write(record, mapper.writeValueAsBytes(state));
    }

    private Path processingStateFileOfStagedDeployment() {
        return tempRootDir.resolve("deployments")
                .resolve(DeploymentDirectoryManager.getSafeFileName(UNFINISHED_DEPLOYMENT_ID))
                .resolve("processing_state.json");
    }

    private DeploymentDirectoryManager.LaunchRecovery launchRecoveryRecorded() throws IOException {
        Path record = processingStateFileOfStagedDeployment();
        Object recovery = new com.fasterxml.jackson.databind.ObjectMapper().readValue(record.toFile(), Map.class)
                .get("launchRecovery");
        return recovery == null ? null : DeploymentDirectoryManager.LaunchRecovery.valueOf(recovery.toString());
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static List<String> logMessagesDuring(Runnable action) {
        List<String> messages = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> messages.add(m.getMessage());
        Slf4jLogAdapter.addGlobalListener(listener);
        try {
            action.run();
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
        return messages;
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
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
        verify(kernelLifecycle).launch();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_rollback_bootstrap_finishes_THEN_prepare_restart_into_kernel_rollback()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(ROLLBACK_BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
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
    void GIVEN_kernel_launch_WHEN_rollback_bootstrap_fails_THEN_inject_deployment(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        doNothing().when(kernelLifecycle).launch();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(ROLLBACK_BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
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

        verify(deployment).setDeploymentStage(eq(ROLLBACK_BOOTSTRAP));
        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertThat(deployments.toArray(), hasSize(1));
    }

    @Test
    void GIVEN_no_launch_param_file_WHEN_persistInitialLaunchParams_THEN_write_jvm_args_to_file() throws Exception {
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        Path noFile = tempRootDir.resolve("noFile");
        doReturn(noFile).when(kernelAlternatives).getLaunchParamsPath();

        kernel.persistInitialLaunchParams(kernelAlternatives, deviceConfiguration.getNucleusComponentName());
        verify(kernelAlternatives).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_existing_launch_param_file_WHEN_persistInitialLaunchParams_THEN_skip() throws Exception {
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();

        kernel.persistInitialLaunchParams(kernelAlternatives, deviceConfiguration.getNucleusComponentName());
        verify(kernelAlternatives, times(0)).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_recipe_WHEN_initializeNucleusLifecycleConfig_THEN_init_lifecycle_and_dependencies() {
        String lifecycle = "lifecycle";
        String step = "step";
        Object interpolatedLifecycle = new HashMap<String, String>() {{
            put(lifecycle, step);
        }};
        ComponentRecipe componentRecipe = ComponentRecipe.builder()
                .lifecycle((Map<String, Object>) interpolatedLifecycle).build();

        kernel.initializeNucleusLifecycleConfig(deviceConfiguration.getNucleusComponentName(), componentRecipe);

        Topics nucleusLifecycle = kernel.getConfig().lookupTopics(DEFAULT_VALUE_TIMESTAMP,
                GreengrassService.SERVICES_NAMESPACE_TOPIC, deviceConfiguration.getNucleusComponentName(),
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        assertEquals(nucleusLifecycle.children.size(), 1);
        String value = Coerce.toString(nucleusLifecycle.find(lifecycle));
        assertEquals(value, step);
    }

    @Test
    void GIVEN_version_WHEN_initializeNucleusVersion_THEN_init_component_version_and_env_var() {
        String nucleusComponentVersion = "test-version";
        kernel.initializeNucleusVersion(deviceConfiguration.getNucleusComponentName(), nucleusComponentVersion);

        String versionTopic = Coerce.toString(kernel.getConfig().lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                deviceConfiguration.getNucleusComponentName(), VERSION_CONFIG_KEY));
        assertEquals(nucleusComponentVersion, versionTopic);
        String envTopic = Coerce.toString(kernel.getConfig().lookup(GreengrassService.SETENV_CONFIG_NAMESPACE,
                Kernel.GGC_VERSION_ENV));
        assertEquals(nucleusComponentVersion, envTopic);
    }

    @Test
    void GIVEN_component_store_already_setup_WHEN_initializeComponentStore_THEN_do_nothing(
            @Mock ComponentStore componentStore, @Mock NucleusPaths nucleusPaths) throws Exception{
        Semver nucleusComponentVersion = new Semver("1.0.0");
        Path recipePath = tempRootDir.resolve("mockRecipe");
        Files.createFile(recipePath);
        doReturn(recipePath).when(componentStore).resolveRecipePath(any());
        doReturn(tempRootDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        Kernel spyKernel = spy(kernel);
        kernel.initializeComponentStore(mock(KernelAlternatives.class),
                deviceConfiguration.getNucleusComponentName(), nucleusComponentVersion, recipePath, tempRootDir);

        verify(spyKernel, times(0)).copyUnpackedNucleusArtifacts(any(), any());
        verify(componentStore, times(0)).savePackageRecipe(any(), any());
    }

    @Test
    void GIVEN_component_store_not_setup_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path dstRecipeDir, @TempDir Path dstArtifactsDir, @TempDir Path unpackDir,
            @Mock ComponentStore componentStore, @Mock NucleusPaths nucleusPaths) throws Exception {
        Semver nucleusComponentVersion = new Semver("1.0.0");
        Path dstRecipePath = dstRecipeDir.resolve("recipe.yaml");
        doReturn(dstRecipePath).when(componentStore).resolveRecipePath(any());
        doReturn(dstArtifactsDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(deviceConfiguration.getNucleusComponentName()).componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        kernel.initializeComponentStore(mock(KernelAlternatives.class), deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion, mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion);

        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(dstArtifactsDir);
    }

    @Test
    void GIVEN_unpack_dir_is_nucleus_root_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path unpackDir, @Mock ComponentStore componentStore) throws Exception {
        Semver nucleusComponentVersion = new Semver("1.0.0");

        // Set up Nucleus root
        NucleusPaths nucleusPaths = new NucleusPaths("mock_loader_logs.log");
        nucleusPaths.setRootPath(unpackDir);
        nucleusPaths.initPaths(unpackDir, unpackDir.resolve("work"), unpackDir.resolve("packages"),
                unpackDir.resolve("config"), unpackDir.resolve("alts"), unpackDir.resolve("deployments"),
                unpackDir.resolve("cli_ipc_info"), unpackDir.resolve("bin"));
        Files.createFile(nucleusPaths.binPath().resolve("greengrass-cli"));
        Files.createFile(nucleusPaths.recipePath().resolve("someRecipe.yaml"));

        Path actualRecipe = nucleusPaths.recipePath().resolve("nucleusRecipe.yaml");
        doReturn(actualRecipe).when(componentStore).resolveRecipePath(any());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        // Set up unpack dir in Nucleus root
        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(deviceConfiguration.getNucleusComponentName())
                        .componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        // Should only copy artifact files to component store
        kernel.initializeComponentStore(mock(KernelAlternatives.class), deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion, mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion);
        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(nucleusPaths.unarchiveArtifactPath(
                componentIdentifier, DEFAULT_NUCLEUS_COMPONENT_NAME.toLowerCase()));
    }
    @Test
    void GIVEN_all_services_autoStartable_THEN_all_services_in_services_to_track() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(true);
        when(service2.shouldAutoStart()).thenReturn(true);
        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2));

        when(kernel.orderedDependencies()).thenReturn(Arrays.asList(service1, service2));

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertEquals(services, result);
        assertEquals(2, result.size());
    }

    @Test
    void GIVEN_no_autoStartableServices_THEN_services_to_track_list_empty() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(false);
        when(service2.shouldAutoStart()).thenReturn(false);
        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2));

        when(kernel.orderedDependencies()).thenReturn(services);
        when(service1.getHardDependers()).thenReturn(Collections.emptyList());
        when(service2.getHardDependers()).thenReturn(Collections.emptyList());

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void GIVEN_mixed_autoStartableServices_THEN_services_to_track_only_contain_autoStartableServices() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(true);
        when(service2.shouldAutoStart()).thenReturn(false);
        when(service3.shouldAutoStart()).thenReturn(true);
        when(service4.shouldAutoStart()).thenReturn(true);

        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2, service3, service4));

        // service3 depends on service2 (non-auto-startable)
        when(service2.getHardDependers()).thenReturn(Arrays.asList(service3));
        when(service3.getHardDependers()).thenReturn(Collections.emptyList());

        when(kernel.orderedDependencies()).thenReturn(services);

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains(service1));
        assertTrue(result.contains(service4));
        assertFalse(result.contains(service2));
        assertFalse(result.contains(service3));
    }

    @Test
    void GIVEN_chained_services_THEN_services_to_track_only_contain_autoStartableServices() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(false);
        when(service2.shouldAutoStart()).thenReturn(true);
        when(service3.shouldAutoStart()).thenReturn(true);

        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2, service3));

        // service2 depends on service1, service3 depends on service2
        when(service1.getHardDependers()).thenReturn(Arrays.asList(service2));
        when(service2.getHardDependers()).thenReturn(Arrays.asList(service3));
        when(service3.getHardDependers()).thenReturn(Collections.emptyList());

        when(kernel.orderedDependencies()).thenReturn(services);

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertTrue(result.isEmpty());
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

    static class MockNucleusUnpackDir {
        Path root;
        Path conf;
        @Getter
        Path bin;
        Path lib;
        Path license;
        @Getter
        Path confRecipe;
        Path libJar;
        Path binLoader;
        Path binTemplate;

        public MockNucleusUnpackDir(Path root) {
            this.root = root;
            this.conf = root.resolve("conf");
            this.bin = root.resolve("bin");
            this.lib = root.resolve("lib");
            this.license = root.resolve("LICENSE");
            this.confRecipe = conf.resolve("recipe.yaml");
            this.libJar = lib.resolve("Greengrass.jar");
            this.binLoader = bin.resolve("loader");
            this.binTemplate = bin.resolve("greengrass.service.template");
        }

        public void setup(String recipeContent) throws IOException {
            Utils.createPaths(conf);
            Utils.createPaths(bin);
            Utils.createPaths(lib);

            for (Path file : Arrays.asList(license, libJar, binLoader, binTemplate)) {
                try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                    writer.append(file.getFileName().toString());
                }
            }
            FileUtils.writeStringToFile(confRecipe.toFile(), recipeContent);
        }

        public void assertDirectoryEquals(Path actual) {
            assertThat(Arrays.asList(actual.toFile().list()), containsInAnyOrder("conf", "bin", "lib", "LICENSE"));
            assertThat(Arrays.asList(actual.resolve("bin").toFile().list()), containsInAnyOrder(
                    "loader", "greengrass.service.template"));
            assertThat(Arrays.asList(actual.resolve("conf").toFile().list()), containsInAnyOrder("recipe.yaml"));
            assertThat(Arrays.asList(actual.resolve("lib").toFile().list()), containsInAnyOrder("Greengrass.jar"));
        }
    }
}
