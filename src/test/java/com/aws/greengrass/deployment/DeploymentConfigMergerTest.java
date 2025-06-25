/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.activator.DefaultActivator;
import com.aws.greengrass.deployment.activator.DeploymentActivator;
import com.aws.greengrass.deployment.activator.DeploymentActivatorFactory;
import com.aws.greengrass.deployment.activator.KernelUpdateActivator;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.UpdateAction;
import com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.exceptions.misusing.InvalidUseOfMatchersException;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.WAIT_SVC_START_POLL_INTERVAL_MILLISEC;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS;


@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentConfigMergerTest {

    private final Logger logger = LogManager.getLogger(this.getClass());

    @Mock
    private Kernel kernel;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private DynamicComponentConfigurationValidator validator;
    @Mock
    private ExecutorService executorService;
    @Mock
    private Context context;

    @BeforeEach
    void beforeEach() {
        lenient().when(kernel.getContext()).thenReturn(context);
        lenient().when(validator.validate(anyMap(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void afterEach() throws Exception {
        context.close();
    }

    @Test
    void GIVEN_AggregateServicesChangeManager_WHEN_initialized_THEN_compute_service_to_add_or_remove()
            throws Exception {
        GreengrassService oldService = createMockGreengrassService("oldService");
        GreengrassService existingService = createMockGreengrassService("existingService");
        Collection<GreengrassService> orderedDependencies = Arrays.asList(oldService, existingService);
        when(kernel.orderedDependencies()).thenReturn(orderedDependencies);
        when(kernel.locate("existingService")).thenReturn(existingService);
        GreengrassService newService = mock(GreengrassService.class);
        when(kernel.locate("oldService")).thenReturn(newService);
        when(kernel.locate("newService")).thenReturn(newService);
        when(kernel.locateIgnoreError("newService")).thenReturn(newService);

        Map<String, Object> newConfig = new HashMap<>();
        newConfig.put("newService", new Object());
        newConfig.put("existingService", new Object());

        DeploymentConfigMerger.AggregateServicesChangeManager manager =
                new DeploymentConfigMerger.AggregateServicesChangeManager(kernel, newConfig);

        assertEquals(newOrderedSet("newService"), manager.getServicesToAdd());
        assertEquals(newOrderedSet("oldService"), manager.getServicesToRemove());
        assertEquals(newOrderedSet("existingService"), manager.getServicesToUpdate());

        // test createRollbackManager()
        DeploymentConfigMerger.AggregateServicesChangeManager toRollback = manager.createRollbackManager();

        assertEquals(newOrderedSet("newService"), toRollback.getServicesToRemove());
        assertEquals(newOrderedSet("oldService"), toRollback.getServicesToAdd());
        assertEquals(newOrderedSet("existingService"), toRollback.getServicesToUpdate());

        // test servicesToTrack()
        when(newService.shouldAutoStart()).thenReturn(true);
        assertEquals(newOrderedSet(newService, existingService), manager.servicesToTrack());

        // test startNewServices()
        manager.startNewServices();
        verify(newService, times(1)).requestStart();

        // test reinstallBrokenServices()
        when(existingService.currentOrReportedStateIs(State.BROKEN)).thenReturn(false);
        manager.reinstallBrokenServices();
        verify(existingService, times(0)).requestReinstall();

        when(existingService.currentOrReportedStateIs(State.BROKEN)).thenReturn(true);
        manager.reinstallBrokenServices();
        verify(existingService, times(1)).requestReinstall();
    }

    @Test
    void GIVEN_AggregateServicesChangeManager_WHEN_removeObsoleteService_THEN_obsolete_services_are_removed()
            throws Exception {
        // GIVEN
        GreengrassService oldService = createMockGreengrassService("oldService", kernel);
        when(oldService.isBuiltin()).thenReturn(false);

        GreengrassService existingAutoStartService = createMockGreengrassService("existingAutoStartService", kernel);
        when(existingAutoStartService.isBuiltin()).thenReturn(true);

        GreengrassService existingService = createMockGreengrassService("existingService", kernel);

        Collection<GreengrassService> orderedDependencies =
                Arrays.asList(oldService, existingService, existingAutoStartService);
        when(kernel.orderedDependencies()).thenReturn(orderedDependencies);

        Map<String, Object> newConfig = new HashMap<>();
        newConfig.put("existingService", new Object());

        DeploymentConfigMerger.AggregateServicesChangeManager manager =
                new DeploymentConfigMerger.AggregateServicesChangeManager(kernel, newConfig);

        // WHEN
        Topics oldServiceTopics = mock(Topics.class);
        when(kernel.findServiceTopic("oldService")).thenReturn(oldServiceTopics);

        CountDownLatch removeComplete = new CountDownLatch(1);
        CompletableFuture<Void> oldServiceClosed = new CompletableFuture<>();
        when(oldService.close()).thenReturn(oldServiceClosed);

        new Thread(() -> {
            try {
                manager.removeObsoleteServices();
                removeComplete.countDown();
            } catch (InterruptedException | ServiceUpdateException e) {
                return;
            }
        }).start();

        // THEN
        // assert blocking on service closed.
        assertFalse(removeComplete.await(1000, TimeUnit.MILLISECONDS));

        oldServiceClosed.complete(null);
        assertTrue(removeComplete.await(200, TimeUnit.MILLISECONDS));

        // assert other services are not removed
        verify(existingAutoStartService, times(0)).close();
        verify(existingService, times(0)).close();
        verify(oldService, times(1)).close();

        // assert obsolete service is removed from context and config.
        verify(oldServiceTopics, times(1)).remove();
        verify(context, times(1)).remove("oldService");
    }

    @Test
    void GIVEN_AggregateServicesChangeManager_WHEN_startNewServices_THEN_start_services_should_auto_start()
            throws Exception {
        // setup
        GreengrassService builtinService = mock(GreengrassService.class);
        when(kernel.locateIgnoreError("builtinService")).thenReturn(builtinService);
        when(builtinService.shouldAutoStart()).thenReturn(true);

        GreengrassService userLambdaService = mock(GreengrassService.class);
        when(kernel.locateIgnoreError("userLambdaService")).thenReturn(userLambdaService);
        when(userLambdaService.shouldAutoStart()).thenReturn(false);

        Collection<GreengrassService> orderedDependencies = Arrays.asList();
        when(kernel.orderedDependencies()).thenReturn(orderedDependencies);

        Map<String, Object> newConfig = new HashMap<>();
        newConfig.put("builtinService", new Object());
        newConfig.put("userLambdaService", new Object());

        DeploymentConfigMerger.AggregateServicesChangeManager manager =
                new DeploymentConfigMerger.AggregateServicesChangeManager(kernel, newConfig);
        assertEquals(newOrderedSet("builtinService", "userLambdaService"), manager.getServicesToAdd());

        // test startNewServices()
        manager.startNewServices();
        verify(builtinService, times(1)).requestStart();
        verify(userLambdaService, times(0)).requestStart();
    }

    @Test
    void GIVEN_waitForServicesToStart_WHEN_service_reached_desired_state_THEN_return_successfully()
            throws Exception {
        // GIVEN
        GreengrassService mockService = mock(GreengrassService.class);

        // service is in BROKEN state before merge
        final AtomicReference<State> mockState = new AtomicReference<>(State.BROKEN);
        doAnswer((invocation) -> mockState.get()).when(mockService).getState();
        doReturn((long) 1).when(mockService).getStateModTime();

        final AtomicBoolean mockReachedDesiredState = new AtomicBoolean(false);
        doAnswer((invocation) -> mockReachedDesiredState.get()).when(mockService).reachedDesiredState();

        CountDownLatch serviceStarted = new CountDownLatch(1);
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        new Thread(() -> {
            try {
                DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(mockService), System.currentTimeMillis(),
                        kernel, future);
                serviceStarted.countDown();
            } catch (ServiceUpdateException | InterruptedException e) {
                logger.error("Fail in waitForServicesToStart", e);
            }
        }).start();

        // assert waitForServicesToStart didn't finish
        assertFalse(serviceStarted.await(3 * WAIT_SVC_START_POLL_INTERVAL_MILLISEC, TimeUnit.MILLISECONDS));

        // WHEN
        mockState.set(State.RUNNING);
        mockReachedDesiredState.set(true);

        // THEN
        assertTrue(serviceStarted.await(3 * WAIT_SVC_START_POLL_INTERVAL_MILLISEC, TimeUnit.MILLISECONDS));
        assertFalse(future.isDone());
    }

    @Test
    void GIVEN_waitForServicesToStart_WHEN_service_is_broken_after_merge_THEN_throw() {
        // stateModTime is larger than mergeTime
        long stateModTime = 10;
        long mergeTime = 1;

        GreengrassService normalService = mock(GreengrassService.class);
        when(normalService.getState()).thenReturn(State.INSTALLED);
        when(normalService.reachedDesiredState()).thenReturn(false);

        GreengrassService brokenService = mock(GreengrassService.class);
        when(brokenService.getState()).thenReturn(State.BROKEN);
        when(brokenService.getStateModTime()).thenReturn(stateModTime);

        CompletableFuture<DeploymentResult> future1 = new CompletableFuture<>();
        assertThrows(ServiceUpdateException.class, () -> {
            DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(normalService, brokenService), mergeTime,
                    kernel, future1);
        });
        assertFalse(future1.isDone());

        CompletableFuture<DeploymentResult> future2 = new CompletableFuture<>();
        assertThrows(ServiceUpdateException.class, () -> {
            DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(brokenService, normalService), mergeTime,
                    kernel, future2);
        });
        assertFalse(future2.isDone());
    }

    @Test
    void GIVEN_waitForServicesToStart_WHEN_deployment_is_cancelled_THEN_return_successfully()
            throws Exception {
        // GIVEN
        GreengrassService mockService = mock(GreengrassService.class);
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        CountDownLatch stoppedWaiting = new CountDownLatch(1);
        new Thread(() -> {
            try {
                DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(mockService), System.currentTimeMillis(),
                        kernel, totallyCompleteFuture);
                stoppedWaiting.countDown();
            } catch (ServiceUpdateException | InterruptedException e) {
                logger.error("Fail in waitForServicesToStart", e);
            }
        }).start();

        // assert waitForServicesToStart didn't finish
        assertFalse(stoppedWaiting.await(3 * WAIT_SVC_START_POLL_INTERVAL_MILLISEC, TimeUnit.MILLISECONDS));

        // WHEN
        totallyCompleteFuture.cancel(false);

        // THEN
        assertTrue(stoppedWaiting.await(3 * WAIT_SVC_START_POLL_INTERVAL_MILLISEC, TimeUnit.MILLISECONDS));
    }

    @Test
    void GIVEN_deployment_WHEN_check_safety_selected_THEN_check_safety_before_update() throws Exception {
        UpdateSystemPolicyService updateSystemPolicyService = mock(UpdateSystemPolicyService.class);
        when(context.get(UpdateSystemPolicyService.class)).thenReturn(updateSystemPolicyService);
        DeploymentActivatorFactory deploymentActivatorFactory = mock(DeploymentActivatorFactory.class);
        DeploymentActivator deploymentActivator = mock(DeploymentActivator.class);
        when(deploymentActivatorFactory.getDeploymentActivator(any())).thenReturn(deploymentActivator);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);

        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel, deviceConfiguration, validator, executorService);

        DeploymentDocument doc = new DeploymentDocument();
        doc.setConfigurationArn("NoSafetyCheckDeploy");
        doc.setComponentUpdatePolicy(
                new ComponentUpdatePolicy(0, SKIP_NOTIFY_COMPONENTS));


        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>(), System.currentTimeMillis());
        verify(updateSystemPolicyService, times(0)).addUpdateAction(any(), any());

        doc.setConfigurationArn("DeploymentId");
        doc.setComponentUpdatePolicy(
                new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>(), System.currentTimeMillis());

        verify(updateSystemPolicyService).addUpdateAction(any(), any());
    }

    @Test
    void GIVEN_deployment_WHEN_task_cancelled_THEN_update_is_cancelled() throws Throwable {
        ArgumentCaptor<UpdateAction> cancelledTaskCaptor = ArgumentCaptor.forClass(UpdateAction.class);
        UpdateSystemPolicyService updateSystemPolicyService = mock(UpdateSystemPolicyService.class);
        DeploymentActivatorFactory factory = mock(DeploymentActivatorFactory.class);
        when(factory.getDeploymentActivator(anyMap())).thenReturn(mock(KernelUpdateActivator.class));

        when(context.get(any())).thenAnswer(invocationOnMock -> {
            Object argument = invocationOnMock.getArgument(0);
            if (UpdateSystemPolicyService.class.equals(argument)) {
                return updateSystemPolicyService;
            } else if (DeploymentActivatorFactory.class.equals(argument)) {
                return factory;
            }
            throw new InvalidUseOfMatchersException(String.format("Argument %s does not match", argument));
        });

        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel, deviceConfiguration, validator, executorService);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getDeploymentId()).thenReturn("DeploymentId");
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, NOTIFY_COMPONENTS));

        Future<DeploymentResult> fut = merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>(), System.currentTimeMillis());

        verify(updateSystemPolicyService)
                .addUpdateAction(any(), cancelledTaskCaptor.capture());

        assertEquals(0, cancelledTaskCaptor.getValue().getTimeout());
        assertEquals("DeploymentId", cancelledTaskCaptor.getValue().getDeploymentId());
        assertTrue(cancelledTaskCaptor.getValue().isGgcRestart());
        // WHEN
        fut.cancel(true);
        cancelledTaskCaptor.getValue().getAction().run();

        // THEN
        verify(doc, times(0)).getFailureHandlingPolicy();
    }

    @Test
    void GIVEN_deployment_WHEN_task_not_cancelled_THEN_update_is_continued() throws Throwable {
        ArgumentCaptor<UpdateAction> taskCaptor = ArgumentCaptor.forClass(UpdateAction.class);
        UpdateSystemPolicyService updateSystemPolicyService = mock(UpdateSystemPolicyService.class);
        when(context.get(UpdateSystemPolicyService.class)).thenReturn(updateSystemPolicyService);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(false);
        when(context.get(BootstrapManager.class)).thenReturn(bootstrapManager);
        DefaultActivator defaultActivator = mock(DefaultActivator.class);
        when(context.get(DefaultActivator.class)).thenReturn(defaultActivator);

        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel, deviceConfiguration, validator, executorService);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getDeploymentId()).thenReturn("DeploymentId");
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>(), System.currentTimeMillis());

        verify(updateSystemPolicyService).addUpdateAction(any(), taskCaptor.capture());

        assertEquals(0, taskCaptor.getValue().getTimeout());
        assertEquals("DeploymentId", taskCaptor.getValue().getDeploymentId());
        assertFalse(taskCaptor.getValue().isGgcRestart());
        // WHEN
        taskCaptor.getValue().getAction().run();

        // THEN
        verify(defaultActivator, times(1)).activate(any(), any(), any(Long.class), any());
    }

    @Test
    void GIVEN_deployment_activate_WHEN_deployment_has_new_config_THEN_new_config_is_validated(ExtensionContext extensionContext) throws Throwable {
        ArgumentCaptor<UpdateAction> taskCaptor = ArgumentCaptor.forClass(UpdateAction.class);
        UpdateSystemPolicyService updateSystemPolicyService = mock(UpdateSystemPolicyService.class);
        when(context.get(UpdateSystemPolicyService.class)).thenReturn(updateSystemPolicyService);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(false);
        when(context.get(BootstrapManager.class)).thenReturn(bootstrapManager);
        DefaultActivator defaultActivator = mock(DefaultActivator.class);
        when(context.get(DefaultActivator.class)).thenReturn(defaultActivator);

        Topic regionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "us-west-2");
        when(deviceConfiguration.getAWSRegion()).thenReturn(regionTopic);
        Topic credEndpointTopic = Topic.of(context, DEVICE_PARAM_IOT_CRED_ENDPOINT, "xxxxxx.credentials.iot.us-west-2.amazonaws.com");
        when(deviceConfiguration.getIotCredentialEndpoint()).thenReturn(credEndpointTopic);
        Topic dataEndpointTopic = Topic.of(context, DEVICE_PARAM_IOT_DATA_ENDPOINT, "xxxxxx-ats.iot.us-west-2.amazonaws.com");
        when(deviceConfiguration.getIotDataEndpoint()).thenReturn(dataEndpointTopic);
        when(deviceConfiguration.getNucleusComponentName()).thenReturn(DEFAULT_NUCLEUS_COMPONENT_NAME);
        ArgumentCaptor<String> regionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> credEndpointCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataEndpointCaptor = ArgumentCaptor.forClass(String.class);
        ignoreExceptionOfType(extensionContext, IOException.class);
        Map<String, Object> newConfig = new HashMap<>();
        Map<String, Object> newConfig2 = new HashMap<>();
        Map<String, Object> newConfig3 = new HashMap<>();
        Map<String, Object> newConfig4 = new HashMap<>();
        newConfig4.put(DEVICE_PARAM_AWS_REGION, "us-east-1");
        newConfig4.put(DEVICE_PARAM_IOT_CRED_ENDPOINT, "xxxxxx.credentials.iot.us-east-1.amazonaws.com");
        newConfig4.put(DEVICE_PARAM_IOT_DATA_ENDPOINT, "xxxxxx-ats.iot.us-east-1.amazonaws.com");

        newConfig3.put(CONFIGURATION_CONFIG_KEY, newConfig4);
        newConfig2.put(DEFAULT_NUCLEUS_COMPONENT_NAME, newConfig3);
        newConfig.put(SERVICES_NAMESPACE_TOPIC, newConfig2);
        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel, deviceConfiguration, validator, executorService);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getDeploymentId()).thenReturn("DeploymentId");
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), newConfig, System.currentTimeMillis());

        verify(updateSystemPolicyService).addUpdateAction(any(), taskCaptor.capture());

        assertEquals(0, taskCaptor.getValue().getTimeout());
        assertEquals("DeploymentId", taskCaptor.getValue().getDeploymentId());
        assertFalse(taskCaptor.getValue().isGgcRestart());
        // WHEN
        taskCaptor.getValue().getAction().run();

        // THEN
        verify(defaultActivator, times(1)).activate(any(), any(), any(Long.class), any());

        verify(deviceConfiguration, times(1)).validateEndpoints(regionCaptor.capture(), credEndpointCaptor.capture(), dataEndpointCaptor.capture());
        assertNotNull(regionCaptor.getValue());
        assertEquals("us-east-1", regionCaptor.getValue());
        assertNotNull(credEndpointCaptor.getValue());
        assertEquals("xxxxxx.credentials.iot.us-east-1.amazonaws.com", credEndpointCaptor.getValue());
        assertNotNull(dataEndpointCaptor.getValue());
        assertEquals("xxxxxx-ats.iot.us-east-1.amazonaws.com", dataEndpointCaptor.getValue());
    }

    @Test
    void GIVEN_deployment_activate_WHEN_deployment_has_some_new_config_THEN_old_config_is_validated(ExtensionContext extensionContext) throws Throwable {
        ArgumentCaptor<UpdateAction> taskCaptor = ArgumentCaptor.forClass(UpdateAction.class);
        UpdateSystemPolicyService updateSystemPolicyService = mock(UpdateSystemPolicyService.class);
        when(context.get(UpdateSystemPolicyService.class)).thenReturn(updateSystemPolicyService);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(false);
        when(context.get(BootstrapManager.class)).thenReturn(bootstrapManager);
        DefaultActivator defaultActivator = mock(DefaultActivator.class);
        when(context.get(DefaultActivator.class)).thenReturn(defaultActivator);

        Topic regionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "us-west-2");
        when(deviceConfiguration.getAWSRegion()).thenReturn(regionTopic);
        Topic credEndpointTopic = Topic.of(context, DEVICE_PARAM_IOT_CRED_ENDPOINT, "xxxxxx.credentials.iot.us-west-2.amazonaws.com");
        when(deviceConfiguration.getIotCredentialEndpoint()).thenReturn(credEndpointTopic);
        Topic dataEndpointTopic = Topic.of(context, DEVICE_PARAM_IOT_DATA_ENDPOINT, "xxxxxx-ats.iot.us-west-2.amazonaws.com");
        when(deviceConfiguration.getIotDataEndpoint()).thenReturn(dataEndpointTopic);
        when(deviceConfiguration.getNucleusComponentName()).thenReturn(DEFAULT_NUCLEUS_COMPONENT_NAME);
        ArgumentCaptor<String> regionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> credEndpointCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataEndpointCaptor = ArgumentCaptor.forClass(String.class);
        ignoreExceptionOfType(extensionContext, IOException.class);
        Map<String, Object> newConfig = new HashMap<>();
        Map<String, Object> newConfig2 = new HashMap<>();
        Map<String, Object> newConfig3 = new HashMap<>();
        Map<String, Object> newConfig4 = new HashMap<>();
        newConfig4.put(DEVICE_PARAM_AWS_REGION, "us-east-1");
        newConfig3.put(CONFIGURATION_CONFIG_KEY, newConfig4);
        newConfig2.put(DEFAULT_NUCLEUS_COMPONENT_NAME, newConfig3);
        newConfig.put(SERVICES_NAMESPACE_TOPIC, newConfig2);
        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel, deviceConfiguration, validator, executorService);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getDeploymentId()).thenReturn("DeploymentId");
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), newConfig, System.currentTimeMillis());

        verify(updateSystemPolicyService).addUpdateAction(any(), taskCaptor.capture());

        assertEquals(0, taskCaptor.getValue().getTimeout());
        assertEquals("DeploymentId", taskCaptor.getValue().getDeploymentId());
        assertFalse(taskCaptor.getValue().isGgcRestart());
        // WHEN
        taskCaptor.getValue().getAction().run();

        // THEN
        verify(defaultActivator, times(1)).activate(any(), any(), any(Long.class), any());

        verify(deviceConfiguration, times(1)).validateEndpoints(regionCaptor.capture(), credEndpointCaptor.capture(), dataEndpointCaptor.capture());

        assertNotNull(regionCaptor.getValue());
        assertEquals("us-east-1", regionCaptor.getValue());
        assertNotNull(credEndpointCaptor.getValue());
        assertEquals("xxxxxx.credentials.iot.us-west-2.amazonaws.com", credEndpointCaptor.getValue());
        assertNotNull(dataEndpointCaptor.getValue());
        assertEquals("xxxxxx-ats.iot.us-west-2.amazonaws.com", dataEndpointCaptor.getValue());

    }

    private Deployment createMockDeployment(DeploymentDocument doc) {
        Deployment deployment = mock(Deployment.class);
        doReturn(doc).when(deployment).getDeploymentDocumentObj();
        return deployment;
    }

    private GreengrassService createMockGreengrassService(String name) {
        GreengrassService service = mock(GreengrassService.class);
        lenient().when(service.getName()).thenReturn(name);
        lenient().when(service.getServiceName()).thenReturn(name);
        return service;
    }

    private GreengrassService createMockGreengrassService(String name, Kernel kernel) throws ServiceLoadException {
        GreengrassService service = mock(GreengrassService.class);
        lenient().when(service.getName()).thenReturn(name);
        lenient().when(service.getServiceName()).thenReturn(name);
        lenient().when(kernel.locate(name)).thenReturn(service);
        return service;
    }

    private static <T> Set<T> newOrderedSet(T... objs) {
        Set<T> set = new LinkedHashSet<>();
        Collections.addAll(set, objs);
        return set;
    }
}
