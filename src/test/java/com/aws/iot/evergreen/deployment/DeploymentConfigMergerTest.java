package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.Crashable;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.activator.DefaultActivator;
import com.aws.iot.evergreen.deployment.activator.DeploymentActivator;
import com.aws.iot.evergreen.deployment.activator.DeploymentActivatorFactory;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.ComponentUpdatePolicy;
import com.aws.iot.evergreen.deployment.model.ComponentUpdatePolicyAction;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.UpdateSystemSafelyService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.WAIT_SVC_START_POLL_INTERVAL_MILLISEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class DeploymentConfigMergerTest {

    private final Logger logger = LogManager.getLogger(this.getClass());

    @Mock
    public Kernel kernel;

    @Mock
    public Context context;

    @BeforeEach
    public void beforeEach() {
        lenient().when(kernel.getContext()).thenReturn(context);
    }

    @AfterEach
    public void afterEach() throws Exception {
        context.close();
    }

    @Test
    public void GIVEN_AggregateServicesChangeManager_WHEN_initialized_THEN_compute_service_to_add_or_remove()
            throws Exception {
        EvergreenService oldService = createMockEvergreenService("oldService");
        EvergreenService existingService = createMockEvergreenService("existingService");
        Collection<EvergreenService> orderedDependencies = Arrays.asList(oldService, existingService);
        when(kernel.orderedDependencies()).thenReturn(orderedDependencies);

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
        when(kernel.locate("existingService")).thenReturn(existingService);
        EvergreenService newService = mock(EvergreenService.class);
        when(kernel.locate("newService")).thenReturn(newService);
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
    public void GIVEN_AggregateServicesChangeManager_WHEN_removeObsoleteService_THEN_obsolete_services_are_removed()
            throws Exception {
        // GIVEN
        EvergreenService oldService = createMockEvergreenService("oldService", kernel);
        when(oldService.isBuiltin()).thenReturn(false);

        EvergreenService existingAutoStartService = createMockEvergreenService("existingAutoStartService", kernel);
        when(existingAutoStartService.isBuiltin()).thenReturn(true);

        EvergreenService existingService = createMockEvergreenService("existingService", kernel);

        Collection<EvergreenService> orderedDependencies =
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
            } catch (InterruptedException | ExecutionException e) {
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
    public void GIVEN_AggregateServicesChangeManager_WHEN_startNewServices_THEN_start_services_should_auto_start()
            throws Exception {
        // setup
        EvergreenService builtinService = mock(EvergreenService.class);
        when(kernel.locate("builtinService")).thenReturn(builtinService);
        when(builtinService.shouldAutoStart()).thenReturn(true);

        EvergreenService userLambdaService = mock(EvergreenService.class);
        when(kernel.locate("userLambdaService")).thenReturn(userLambdaService);
        when(userLambdaService.shouldAutoStart()).thenReturn(false);

        Collection<EvergreenService> orderedDependencies = Arrays.asList();
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
    public void GIVEN_waitForServicesToStart_WHEN_service_reached_desired_state_THEN_return_successfully()
            throws Exception {
        // GIVEN
        EvergreenService mockService = mock(EvergreenService.class);

        // service is in BROKEN state before merge
        final AtomicReference<State> mockState = new AtomicReference<>(State.BROKEN);
        doAnswer((invocation) -> mockState.get()).when(mockService).getState();
        doReturn((long) 1).when(mockService).getStateModTime();

        final AtomicBoolean mockReachedDesiredState = new AtomicBoolean(false);
        doAnswer((invocation) -> mockReachedDesiredState.get()).when(mockService).reachedDesiredState();

        CountDownLatch serviceStarted = new CountDownLatch(1);
        new Thread(() -> {
            try {
                DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(mockService), System.currentTimeMillis());
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
    }

    @Test
    public void GIVEN_waitForServicesToStart_WHEN_service_is_broken_after_merge_THEN_throw() {
        // stateModTime is larger than mergeTime
        long stateModTime = 10;
        long mergeTime = 1;

        EvergreenService normalService = mock(EvergreenService.class);
        when(normalService.getState()).thenReturn(State.INSTALLED);
        when(normalService.reachedDesiredState()).thenReturn(false);

        EvergreenService brokenService = mock(EvergreenService.class);
        when(brokenService.getState()).thenReturn(State.BROKEN);
        when(brokenService.getStateModTime()).thenReturn(stateModTime);

        assertThrows(ServiceUpdateException.class, () -> {
            DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(normalService, brokenService), mergeTime);
        });

        assertThrows(ServiceUpdateException.class, () -> {
            DeploymentConfigMerger.waitForServicesToStart(newOrderedSet(brokenService, normalService), mergeTime);
        });
    }

    @Test
    public void GIVEN_deployment_WHEN_check_safety_selected_THEN_check_safety_before_update() throws Exception {
        UpdateSystemSafelyService updateSystemSafelyService = mock(UpdateSystemSafelyService.class);
        when(context.get(UpdateSystemSafelyService.class)).thenReturn(updateSystemSafelyService);
        DeploymentActivatorFactory deploymentActivatorFactory = mock(DeploymentActivatorFactory.class);
        DeploymentActivator deploymentActivator = mock(DeploymentActivator.class);
        when(deploymentActivatorFactory.getDeploymentActivator(any())).thenReturn(deploymentActivator);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);

        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel);

        DeploymentDocument doc = new DeploymentDocument();
        doc.setDeploymentId("NoSafetyCheckDeploy");
        doc.setComponentUpdatePolicy(
                new ComponentUpdatePolicy(0, ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS));


        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>());
        verify(updateSystemSafelyService, times(0)).addUpdateAction(any(), any());

        doc.setDeploymentId("DeploymentId");
        doc.setComponentUpdatePolicy(
                new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>());

        verify(updateSystemSafelyService).addUpdateAction(any(), any());
    }

    @Test
    public void GIVEN_deployment_WHEN_task_cancelled_THEN_update_is_cancelled() throws Throwable {
        ArgumentCaptor<Crashable> cancelledTaskCaptor = ArgumentCaptor.forClass(Crashable.class);
        UpdateSystemSafelyService updateSystemSafelyService = mock(UpdateSystemSafelyService.class);
        when(context.get(UpdateSystemSafelyService.class)).thenReturn(updateSystemSafelyService);

        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getDeploymentId()).thenReturn("DeploymentId");
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS));

        Future<DeploymentResult> fut = merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>());

        verify(updateSystemSafelyService)
                .addUpdateAction(any(), cancelledTaskCaptor.capture());

        // WHEN
        fut.cancel(true);
        cancelledTaskCaptor.getValue().run();

        // THEN
        verify(doc, times(0)).getFailureHandlingPolicy();
    }

    @Test
    public void GIVEN_deployment_WHEN_task_not_cancelled_THEN_update_is_continued() throws Throwable {
        ArgumentCaptor<Crashable> taskCaptor = ArgumentCaptor.forClass(Crashable.class);
        UpdateSystemSafelyService updateSystemSafelyService = mock(UpdateSystemSafelyService.class);
        when(context.get(UpdateSystemSafelyService.class)).thenReturn(updateSystemSafelyService);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        when(context.get(DeploymentActivatorFactory.class)).thenReturn(deploymentActivatorFactory);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(false);
        when(context.get(BootstrapManager.class)).thenReturn(bootstrapManager);
        DefaultActivator defaultActivator = mock(DefaultActivator.class);
        when(context.get(DefaultActivator.class)).thenReturn(defaultActivator);

        // GIVEN
        DeploymentConfigMerger merger = new DeploymentConfigMerger(kernel);
        DeploymentDocument doc = mock(DeploymentDocument.class);
        when(doc.getComponentUpdatePolicy()).thenReturn(
                new ComponentUpdatePolicy(0, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS));

        merger.mergeInNewConfig(createMockDeployment(doc), new HashMap<>());

        verify(updateSystemSafelyService).addUpdateAction(any(), taskCaptor.capture());

        // WHEN
        taskCaptor.getValue().run();

        // THEN
        verify(defaultActivator, times(1)).activate(any(), any(), any());
    }

    private Deployment createMockDeployment(DeploymentDocument doc) {
        Deployment deployment = mock(Deployment.class);
        doReturn(doc).when(deployment).getDeploymentDocumentObj();
        return deployment;
    }

    private EvergreenService createMockEvergreenService(String name) {
        EvergreenService service = mock(EvergreenService.class);
        when(service.getName()).thenReturn(name);
        return service;
    }

    private EvergreenService createMockEvergreenService(String name, Kernel kernel) throws ServiceLoadException {
        EvergreenService service = mock(EvergreenService.class);
        lenient().when(service.getName()).thenReturn(name);
        lenient().when(kernel.locate(name)).thenReturn(service);
        return service;
    }

    private static <T> Set<T> newOrderedSet(T... objs) {
        Set<T> set = new LinkedHashSet<>();
        Collections.addAll(set, objs);
        return set;
    }
}
