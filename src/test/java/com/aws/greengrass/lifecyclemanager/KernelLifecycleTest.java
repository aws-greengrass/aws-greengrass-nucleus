/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
class KernelLifecycleTest {
    private Kernel mockKernel;
    private KernelCommandLine mockKernelCommandLine;
    private KernelLifecycle kernelLifecycle;
    private Context mockContext;
    private Configuration mockConfig;
    private IPCEventStreamService mockIpcEventStreamService;

    @TempDir
    protected Path tempRootDir;
    private NucleusPaths mockPaths;

    @BeforeEach
    void beforeEach() throws IOException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        mockKernel = mock(Kernel.class);
        mockContext = mock(Context.class);
        mockConfig = mock(Configuration.class);
        mockPaths = mock(NucleusPaths.class);
        mockIpcEventStreamService = mock(IPCEventStreamService.class);
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

        mockKernelCommandLine = Mockito.spy(new KernelCommandLine(mockKernel));
        kernelLifecycle = new KernelLifecycle(mockKernel, mockKernelCommandLine, mockPaths);
    }

    @AfterEach
    void afterEach() {
        kernelLifecycle.shutdown();
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_WHEN_launch_with_autostart_services_THEN_autostarts_added_as_dependencies_of_main()
            throws Exception {
        GreengrassService mockMain = mock(GreengrassService.class);
        GreengrassService mockOthers = mock(GreengrassService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));
        doReturn(mockOthers).when(mockKernel).locate(not(eq("main")));

        // Mock out EZPlugins so I can return a deterministic set of services to be added as auto-start
        EZPlugins pluginMock = mock(EZPlugins.class);
        kernelLifecycle.setStartables(new ArrayList<>());
        when(mockContext.get(EZPlugins.class)).thenReturn(pluginMock);
        doAnswer((i) -> {
            ClassAnnotationMatchProcessor func = i.getArgument(1);

            func.processMatch(UpdateSystemPolicyService.class);
            func.processMatch(DeploymentService.class);

            return null;
        }).when(pluginMock).annotated(eq(ImplementsService.class), any());

        kernelLifecycle.launch();
        // Expect 2 times because I returned 2 plugins from above: SafeUpdate and Deployment
        verify(mockMain, times(2)).addOrUpdateDependency(eq(mockOthers), eq(DependencyType.HARD), eq(true));
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
        verify(mockKernel).writeEffectiveConfig(tempRootDir.resolve("config").resolve("config.yaml"));
        verify(mockKernelCommandLine).setProvidedConfigPathName(eq(overrideConfigPathName));
    }

    @Test
    void GIVEN_provided_external_config_WHEN_read_THEN_read_external_config() throws Exception {
        String providedConfigPathName = "external_config.yaml";
        when(mockKernelCommandLine.getProvidedConfigPathName()).thenReturn(providedConfigPathName);

        kernelLifecycle.initConfigAndTlog();
        verify(mockConfig).read(eq(providedConfigPathName));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig(tempRootDir.resolve("config").resolve("config.yaml"));
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
        File configYaml = mockPaths.configPath().resolve("config.yaml").toFile();
        configYaml.createNewFile();

        kernelLifecycle.initConfigAndTlog();
        verify(mockKernel.getConfig()).read(eq(configYaml.toPath()));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig(tempRootDir.resolve("config").resolve("config.yaml"));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_without_config_THEN_tlog_read_from_disk() throws Exception {
        // Create configTlog so that the kernel will try to read it in
        File configTlog = mockPaths.configPath().resolve("config.tlog").toFile();
        configTlog.createNewFile();

        kernelLifecycle.initConfigAndTlog();
        verify(mockKernel.getConfig()).read(eq(configTlog.toPath()));
        verify(mockKernel).writeEffectiveConfigAsTransactionLog(tempRootDir.resolve("config").resolve("config.tlog"));
        verify(mockKernel).writeEffectiveConfig(tempRootDir.resolve("config").resolve("config.yaml"));
    }

    @Test
    void GIVEN_kernel_WHEN_launch_with_config_THEN_effective_config_written() throws Exception {
        GreengrassService mockMain = mock(GreengrassService.class);
        doReturn(mockMain).when(mockKernel).locate(eq("main"));

        kernelLifecycle.initConfigAndTlog();
        Path configPath = mockPaths.configPath().resolve("config.yaml");
        verify(mockKernel).writeEffectiveConfig(eq(configPath));
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
}
