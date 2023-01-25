/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.provisioning.DeviceIdentityInterface;
import com.aws.greengrass.provisioning.ProvisionConfiguration;
import com.aws.greengrass.provisioning.ProvisionConfiguration.NucleusConfiguration;
import com.aws.greengrass.provisioning.ProvisionConfiguration.SystemConfiguration;
import com.aws.greengrass.provisioning.ProvisionContext;
import com.aws.greengrass.provisioning.ProvisioningConfigUpdateHelper;
import com.aws.greengrass.provisioning.ProvisioningPluginFactory;
import com.aws.greengrass.provisioning.exceptions.RetryableProvisioningException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.DEFAULT_CONFIG_YAML_FILE_READ;
import static com.aws.greengrass.lifecyclemanager.KernelCommandLine.MAIN_SERVICE_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CouplingBetweenObjects")
@ExtendWith(GGExtension.class)
class KernelLifecycleTest {
    private static final String MOCK_IOT_ROLE_ALIAS = "MOCK_IOT_ROLE_ALIAS";
    private static final String MOCK_AWS_REGION = "MOCK_AWS_REGION";
    private static final String MOCK_IOT_DATA_ENDPOINT = "MOCK_IOT_DATA_ENDPOINT";
    private static final String MOCK_IOT_CREDENTIAL_ENDPOINT = "MOCK_IOT_CREDENTIAL_ENDPOINT";

    private static final String MOCK_THING_NAME = "MOCK_THING_NAME";
    private static final String MOCK_ROOT_CA_PATH = "MOCK_ROOT_CA_PATH";
    private static final String MOCK_PRIVATE_KEY_PATH = "MOCK_PRIVATE_KEY_PATH";
    private static final String MOCK_CERTIFICATE_KEY_PATH = "MOCK_CERTIFICATE_KEY_PATH";

    private Kernel mockKernel;
    private KernelCommandLine mockKernelCommandLine;
    private KernelLifecycle kernelLifecycle;
    private Context mockContext;
    private Configuration mockConfig;
    private IPCEventStreamService mockIpcEventStreamService;
    private DeviceConfiguration mockDeviceConfiguration;
    private ProvisioningConfigUpdateHelper mockProvisioningConfigUpdateHelper;
    private ProvisioningPluginFactory mockProvisioningPluginFactory;
    private DeviceIdentityInterface mockProvisioningPlugin;
    private Topics mockServicesConfig;
    private ExecutorService executorService;


    private static Class mockPluginClass;

    @TempDir
    protected Path tempRootDir;
    private NucleusPaths mockPaths;
    private GreengrassService mockOthers;
    private GreengrassService mockMain;

    @BeforeAll
    public static void createMockProvisioningPlugin() {
        DeviceIdentityInterface mockDeviceIdentityInterfaceImpl = new DeviceIdentityInterface() {
            @Override
            public ProvisionConfiguration updateIdentityConfiguration(ProvisionContext provisionContext) throws RetryableProvisioningException {
                return null;
            }

            @Override
            public String name() {
                return null;
            }
        };
        mockPluginClass = mockDeviceIdentityInterfaceImpl.getClass();
    }

    @BeforeEach
    void beforeEach() throws IOException, ServiceLoadException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        mockKernel = mock(Kernel.class);
        mockContext = mock(Context.class);
        mockConfig = mock(Configuration.class);
        mockPaths = mock(NucleusPaths.class);
        mockIpcEventStreamService = mock(IPCEventStreamService.class);
        mockDeviceConfiguration = mock(DeviceConfiguration.class);
        mockProvisioningConfigUpdateHelper = mock(ProvisioningConfigUpdateHelper.class);
        mockProvisioningPluginFactory = mock(ProvisioningPluginFactory.class);
        when(mockConfig.getRoot()).thenReturn(mock(Topics.class));
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockContext.get(IPCEventStreamService.class)).thenReturn(mockIpcEventStreamService);
        when(mockKernel.getContext()).thenReturn(mockContext);
        when(mockPaths.rootPath()).thenReturn(tempRootDir);
        when(mockPaths.configPath()).thenReturn(tempRootDir.resolve("config"));
        Files.createDirectories(tempRootDir.resolve("config"));
        when(mockContext.get(eq(EZPlugins.class))).thenReturn(mock(EZPlugins.class));
        when(mockContext.get(eq(ExecutorService.class))).thenReturn(mock(ExecutorService.class));
        when(mockContext.get(eq(ScheduledExecutorService.class))).thenReturn(mock(ScheduledExecutorService.class));
        when(mockContext.get(eq(DeviceConfiguration.class))).thenReturn(mockDeviceConfiguration);
        when(mockDeviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);


        mockKernelCommandLine = Mockito.spy(new KernelCommandLine(mockKernel));
        kernelLifecycle = new KernelLifecycle(mockKernel, mockKernelCommandLine, mockPaths);
        kernelLifecycle.setProvisioningConfigUpdateHelper(mockProvisioningConfigUpdateHelper);
        kernelLifecycle.setProvisioningPluginFactory(mockProvisioningPluginFactory);

        mockMain = mock(GreengrassService.class);
        mockOthers = mock(GreengrassService.class);
        doReturn(mockMain).when(mockKernel).locateIgnoreError(eq(MAIN_SERVICE_NAME));
        doReturn(mockOthers).when(mockKernel).locate(not(eq(MAIN_SERVICE_NAME)));
    }

    @AfterEach
    void afterEach() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        kernelLifecycle.shutdown();
    }

    @ImplementsService(autostart = true, name = "KernelLifecycle")
    private static class KLF extends GreengrassService {
        public KLF(Topics topics) {
            super(topics);
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_autostart_services_THEN_autostarts_added_as_dependencies_of_main()
            throws InputValidationException {
        // Mock out EZPlugins so I can return a deterministic set of services to be added as auto-start
        EZPlugins pluginMock = mock(EZPlugins.class);
        kernelLifecycle.setStartables(new ArrayList<>());
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ClassAnnotationMatchProcessor func = i.getArgument(1);

            func.processMatch(KLF.class);

            return null;
        }).when(pluginMock).annotated(eq(ImplementsService.class), any());

        kernelLifecycle.launch();
        // Expect 5 times because 4 builtins are already set as autostart + KLF
        verify(mockMain, times(5)).addOrUpdateDependency(eq(mockOthers), eq(DependencyType.HARD), eq(true));
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_without_provisioning_plugin_AND_device_not_provisioned_THEN_device_starts_offline()
            throws Exception {
        when(mockDeviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(false);

        kernelLifecycle.setStartables(new ArrayList<>());
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> null).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();
        // Expect 2 times because I returned 2 plugins from above: SafeUpdate and Deployment
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateNucleusConfiguration(any(NucleusConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateSystemConfiguration(any(SystemConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        verify(mockMain, times(4)).addOrUpdateDependency(eq(mockOthers),
                eq(DependencyType.HARD), eq(true));
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_provisioning_plugin_THEN_plugin_methods_are_invoked()
            throws Exception {

        mockProvisioning();
        when(mockProvisioningPlugin.updateIdentityConfiguration(any())).thenReturn(mock(ProvisionConfiguration.class));
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ImplementingClassMatchProcessor func = i.getArgument(1);
            func.processMatch(mockPluginClass);
            return null;
        }).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();
        verify(mockProvisioningPlugin, timeout(1000).times(1)).updateIdentityConfiguration(any(ProvisionContext.class));
    }



    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_provisioning_plugin_THEN_configuration_is_updated()
            throws Exception {

        mockProvisioning();
        ProvisionConfiguration mockProvisionConfiguration = createMockProvisioningConforguration();
        when(mockProvisioningPlugin.updateIdentityConfiguration(any())).thenReturn(mockProvisionConfiguration);
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ImplementingClassMatchProcessor func = i.getArgument(1);
            func.processMatch(mockPluginClass);
            return null;
        }).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();

        ArgumentCaptor<NucleusConfiguration> nucleusConfigCaptor =
                ArgumentCaptor.forClass(NucleusConfiguration.class);
        verify(mockProvisioningConfigUpdateHelper, timeout(500).times(1))
                .updateNucleusConfiguration(nucleusConfigCaptor.capture()
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));

        ArgumentCaptor<SystemConfiguration> systemConfigCaptor =
                ArgumentCaptor.forClass(SystemConfiguration.class);
        verify(mockProvisioningConfigUpdateHelper, timeout(500).times(1))
                .updateSystemConfiguration(systemConfigCaptor.capture()
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        assertEquals(mockProvisionConfiguration.getNucleusConfiguration(), nucleusConfigCaptor.getValue());
        assertEquals(mockProvisionConfiguration.getSystemConfiguration(), systemConfigCaptor.getValue());

    }


    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_provisioning_plugin_AND_plugin_methods_throw_runtime_Exception_THEN_offline_mode(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        mockProvisioning();
        when(mockProvisioningPlugin.updateIdentityConfiguration(any()))
                .thenThrow(new RuntimeException("Error provisioning"));
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ImplementingClassMatchProcessor func = i.getArgument(1);
            func.processMatch(mockPluginClass);
            return null;
        }).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();
        verify(mockProvisioningPlugin, timeout(1000).times(1)).updateIdentityConfiguration(any(ProvisionContext.class));
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateNucleusConfiguration(any(NucleusConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateSystemConfiguration(any(SystemConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_provisioning_plugin_AND_plugin_methods_throw_retryable_Exception_THEN_plugin_retries_successfully(ExtensionContext context)
            throws Exception {

        ignoreExceptionOfType(context, RetryableProvisioningException.class);
        mockProvisioning();
        doAnswer(new Answer() {
            int count = 0;

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                count++;
                if (count == 1) {
                    throw new RetryableProvisioningException("Retryable error in provisioning");
                } else {
                    return createMockProvisioningConforguration();
                }
            }
        }).when(mockProvisioningPlugin).updateIdentityConfiguration(any());

        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ImplementingClassMatchProcessor func = i.getArgument(1);
            func.processMatch(mockPluginClass);
            return null;
        }).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();
        verify(mockProvisioningPlugin, timeout(2000).times(2)).updateIdentityConfiguration(any(ProvisionContext.class));
        verify(mockProvisioningConfigUpdateHelper, timeout(1000).times(1))
                .updateNucleusConfiguration(any(NucleusConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        verify(mockProvisioningConfigUpdateHelper, times(1))
                .updateSystemConfiguration(any(SystemConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_provisioning_plugin_AND_plugin_methods_throw_retryable_Exception_THEN_plugin_fails_after_max_attempt(ExtensionContext context)
            throws Exception {

        ignoreExceptionOfType(context, RetryableProvisioningException.class);
        mockProvisioning();
        when(mockProvisioningPlugin.updateIdentityConfiguration(any())).thenThrow(new RetryableProvisioningException(
                "Retryable error"));
        EZPlugins pluginMock = mock(EZPlugins.class);
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ImplementingClassMatchProcessor func = i.getArgument(1);
            func.processMatch(mockPluginClass);
            return null;
        }).when(pluginMock).implementing(eq(DeviceIdentityInterface.class), any());

        kernelLifecycle.launch();
        // wait for retries
        Thread.sleep(7000);
        // verification with timeout seems to fail prematurely instead of waiting for the timeout period
        verify(mockProvisioningPlugin, times(3)).updateIdentityConfiguration(any(ProvisionContext.class));
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateNucleusConfiguration(any(NucleusConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
        verify(mockProvisioningConfigUpdateHelper, times(0))
                .updateSystemConfiguration(any(SystemConfiguration.class)
                        , eq(UpdateBehaviorTree.UpdateBehavior.MERGE));
    }

    private void mockProvisioning() throws InstantiationException, IllegalAccessException {


        executorService = Executors.newSingleThreadExecutor();
        when(mockContext.get(eq(ExecutorService.class))).thenReturn(executorService);
        when(mockDeviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(false);
        mockProvisioningPlugin = (DeviceIdentityInterface) mock(mockPluginClass);
        when(mockProvisioningPluginFactory.getPluginInstance(any()))
                .thenReturn(mockProvisioningPlugin);
        mockServicesConfig = mock(Topics.class);
        when(mockConfig.lookupTopics(eq(SERVICES_NAMESPACE_TOPIC))).thenReturn(mockServicesConfig);
        when(mockServicesConfig.lookupTopics(any())).thenReturn(mock(Topics.class));

        kernelLifecycle.setStartables(new ArrayList<>());
    }

    @Test
    void GIVEN_deployment_config_override_WHEN_read_THEN_read_persisted_config() throws Exception {
        String providedConfigPathName = "external_config.yaml";
        String overrideConfigPathName = "target_config.tlog";
        when(mockKernelCommandLine.getProvidedConfigPathName())
                .thenReturn(providedConfigPathName, overrideConfigPathName);

        kernelLifecycle.initConfigAndTlog(overrideConfigPathName);
        verify(mockConfig).read(eq(overrideConfigPathName));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig();
        verify(mockKernelCommandLine).setProvidedConfigPathName(eq(overrideConfigPathName));
    }

    @Test
    void GIVEN_provided_external_config_WHEN_read_THEN_read_external_config() throws Exception {
        String providedConfigPathName = "external_config.yaml";
        when(mockKernelCommandLine.getProvidedConfigPathName()).thenReturn(providedConfigPathName);

        kernelLifecycle.initConfigAndTlog();
        verify(mockConfig).read(eq(providedConfigPathName));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig();
    }

    @Test
    void GIVEN_provided_external_initial_config_WHEN_read_THEN_read_external_config() throws Exception {
        File externalFile = mockPaths.configPath().resolve("external_config.yaml").toFile();
        externalFile.createNewFile();
        when(mockKernelCommandLine.getProvidedInitialConfigPath()).thenReturn(externalFile.toString());
        Path configTlogPath = mockPaths.configPath().resolve("config.tlog");
        Files.copy(Paths.get(this.getClass().getResource("test.tlog").toURI()), configTlogPath);
        kernelLifecycle.initConfigAndTlog();
        verify(mockConfig).read(eq(configTlogPath));
        verify(mockConfig).read(eq(externalFile.toPath()));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig();
    }

    @Test
    void GIVEN_unable_to_read_config_WHEN_read_THEN_throw_RuntimeException(ExtensionContext context) throws Exception {
        String providedConfigPathName = "not_exist_config.yaml";
        when(mockKernelCommandLine.getProvidedConfigPathName()).thenReturn(providedConfigPathName);
        when(mockKernel.getConfig().read(any(String.class))).thenThrow(new IOException());

        ignoreExceptionOfType(context, IOException.class);

        assertThrows(RuntimeException.class, () -> kernelLifecycle.initConfigAndTlog());
        verify(mockConfig).read(eq(providedConfigPathName));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_without_config_THEN_config_read_from_disk() throws Exception {
        // Create configYaml so that the kernel will try to read it in
        File configYaml = mockPaths.configPath().resolve(DEFAULT_CONFIG_YAML_FILE_READ).toFile();
        configYaml.createNewFile();

        kernelLifecycle.initConfigAndTlog();
        verify(mockKernel.getConfig()).read(eq(configYaml.toPath()));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig();
    }

    @Test
    void GIVEN_kernel_WHEN_launch_without_config_THEN_tlog_read_from_disk() throws Exception {
        // Create configTlog so that the kernel will try to read it in
        Path configTlogPath = mockPaths.configPath().resolve("config.tlog");
        Files.copy(Paths.get(this.getClass().getResource("test.tlog").toURI()), configTlogPath);
        kernelLifecycle.initConfigAndTlog();
        verify(mockKernel.getConfig()).read(eq(configTlogPath));
        // Since we read from the tlog, we don't need to re-write the same info
        verify(mockKernel, never()).writeEffectiveConfigAsTransactionLog(
                tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig();
    }

    @Test
    void GIVEN_kernel_WHEN_launch_with_config_THEN_effective_config_written() throws Exception {
        GreengrassService mockMain = mock(GreengrassService.class);
        doReturn(mockMain).when(mockKernel).locateIgnoreError(eq(MAIN_SERVICE_NAME));

        kernelLifecycle.initConfigAndTlog();
        verify(mockKernel).writeEffectiveConfig();
    }

    @Test
    void GIVEN_kernel_WHEN_startupAllServices_THEN_services_started_in_order() {
        GreengrassService service1 = mock(GreengrassService.class);
        GreengrassService service2 = mock(GreengrassService.class);
        GreengrassService service3 = mock(GreengrassService.class);
        GreengrassService service4 = mock(GreengrassService.class);
        when(service1.shouldAutoStart()).thenReturn(true);
        when(service2.shouldAutoStart()).thenReturn(true);
        when(service3.shouldAutoStart()).thenReturn(true);
        when(service4.shouldAutoStart()).thenReturn(true);
        doNothing().when(service1).requestStart();
        doNothing().when(service2).requestStart();
        doNothing().when(service3).requestStart();
        doNothing().when(service4).requestStart();

        CompletableFuture<Void> fut = new CompletableFuture<>();
        fut.complete(null);
        doReturn(fut).when(service1).close();
        doReturn(fut).when(service2).close();
        doReturn(fut).when(service3).close();
        doReturn(fut).when(service4).close();

        doReturn(Arrays.asList(service1, service2, service3, service4)).when(mockKernel).orderedDependencies();

        kernelLifecycle.startupAllServices();

        InOrder inOrder = inOrder(service1, service2, service3, service4);
        inOrder.verify(service1).requestStart();
        inOrder.verify(service2).requestStart();
        inOrder.verify(service3).requestStart();
        inOrder.verify(service4).requestStart();
    }

    @Test
    void GIVEN_kernel_WHEN_shutdown_twice_THEN_only_1_shutdown_happens() {
        doReturn(Collections.emptyList()).when(mockKernel).orderedDependencies();

        kernelLifecycle.shutdown();
        kernelLifecycle.shutdown();

        verify(mockKernel).orderedDependencies();
    }

    @Test
    void GIVEN_kernel_WHEN_shutdown_THEN_shutsdown_services_in_order(ExtensionContext context) throws Exception {
        GreengrassService badService1 = mock(GreengrassService.class);
        GreengrassService service2 = mock(GreengrassService.class);
        GreengrassService service3 = mock(GreengrassService.class);
        GreengrassService service4 = mock(GreengrassService.class);
        GreengrassService badService5 = mock(GreengrassService.class);

        CompletableFuture<Void> fut = new CompletableFuture<>();
        fut.complete(null);
        CompletableFuture<Void> failedFut = new CompletableFuture<>();
        failedFut.completeExceptionally(new Exception("Service1"));

        doReturn(failedFut).when(badService1).close();
        doReturn(fut).when(service2).close();
        doReturn(fut).when(service3).close();
        doReturn(fut).when(service4).close();
        doThrow(new RuntimeException("Service5")).when(badService5).close();

        doReturn(Arrays.asList(badService1, service2, service3, service4, badService5)).when(mockKernel)
                .orderedDependencies();

        // Check that logging of exceptions works as expected
        // Expect 5 then 1 because our OD is 1->5, so reversed is 5->1.
        CountDownLatch seenErrors = new CountDownLatch(2);
        Pair<CompletableFuture<Void>, Consumer<GreengrassLogMessage>> listener =
                TestUtils.asyncAssertOnConsumer((m) -> {
                    if ("service-shutdown-error".equals(m.getEventType())) {
                        if (seenErrors.getCount() == 2) {
                            assertEquals("Service5", m.getCause().getMessage());
                        } else if (seenErrors.getCount() == 1) {
                            assertEquals("Service1", m.getCause().getMessage());
                        }
                        seenErrors.countDown();
                    }
                }, -1);

        ignoreExceptionUltimateCauseWithMessage(context, "Service5");
        ignoreExceptionUltimateCauseWithMessage(context, "Service1");

        Slf4jLogAdapter.addGlobalListener(listener.getRight());
        kernelLifecycle.shutdown(5);
        Slf4jLogAdapter.removeGlobalListener(listener.getRight());
        assertTrue(seenErrors.await(1, TimeUnit.SECONDS));
        listener.getLeft().get(1, TimeUnit.SECONDS);

        InOrder inOrder = inOrder(badService5, service4, service3, service2, badService1); // Reverse ordered
        inOrder.verify(badService5).close();
        inOrder.verify(service4).close();
        inOrder.verify(service3).close();
        inOrder.verify(service2).close();
        inOrder.verify(badService1).close();
    }

    private ProvisionConfiguration createMockProvisioningConforguration() {
        ProvisionConfiguration provisionConfiguration = new ProvisionConfiguration();
        NucleusConfiguration nucleusConfiguration =
                new NucleusConfiguration();
        nucleusConfiguration.setIotRoleAlias(MOCK_IOT_ROLE_ALIAS);
        nucleusConfiguration.setAwsRegion(MOCK_AWS_REGION);
        nucleusConfiguration.setIotDataEndpoint(MOCK_IOT_DATA_ENDPOINT);
        nucleusConfiguration.setIotCredentialsEndpoint(MOCK_IOT_CREDENTIAL_ENDPOINT);
        provisionConfiguration.setNucleusConfiguration(nucleusConfiguration);

        SystemConfiguration systemConfiguration =
                new SystemConfiguration();
        systemConfiguration.setThingName(MOCK_THING_NAME);
        systemConfiguration.setRootCAPath(MOCK_ROOT_CA_PATH);
        systemConfiguration.setPrivateKeyPath(MOCK_PRIVATE_KEY_PATH);
        systemConfiguration.setCertificateFilePath(MOCK_CERTIFICATE_KEY_PATH);
        provisionConfiguration.setSystemConfiguration(systemConfiguration);
        return provisionConfiguration;
    }
}
