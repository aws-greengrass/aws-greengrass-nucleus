/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.Crashable;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.lifecyclemanager.KernelTest.ExpectedStateTransition;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;

import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ServiceDependencyLifecycleTest extends BaseITCase {
    private static final String CustomerApp = "CustomerApp";
    private static final String HardDependency = "HardDependency";
    private static final String SoftDependency = "SoftDependency";
    private static final Logger logger = LogManager.getLogger(ServiceDependencyLifecycleTest.class);

    private static final int TEST_ROUTINE_SHORT_TIMEOUT = 15;
    private static final int TEST_ROUTINE_MEDIUM_TIMEOUT = 20;
    private static final int TEST_ROUTINE_LONG_TIMEOUT = 30;

    private Kernel kernel;

    @AfterEach
    void teardown() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @SuppressWarnings({"PMD.LooseCoupling", "PMD.CloseResource"})
    private static void testRoutine(long timeoutSeconds, Kernel kernel, Crashable action, String actionName,
                                    LinkedList<ExpectedStateTransition> expectedStateTransitions,
                                    Set<ExpectedStateTransition> unexpectedStateTransitions)
            throws Throwable {
        Context context = kernel.getContext();
        CountDownLatch assertionLatch = new CountDownLatch(1);
        List<ExpectedStateTransition> unexpectedSeenInOrder = new LinkedList<>();

        GlobalStateChangeListener listener = (GreengrassService service, State oldState, State newState) -> {
            if (!expectedStateTransitions.isEmpty()) {
                ExpectedStateTransition expected = expectedStateTransitions.peek();

                if (service.getName().equals(expected.serviceName) && oldState.equals(expected.was) && newState
                        .equals(expected.current)) {
                    logger.atWarn().kv("expected", expected).log("Just saw expected state event for service");
                    expectedStateTransitions.pollFirst();
                }
                if (expectedStateTransitions.isEmpty()) {
                    assertionLatch.countDown();
                }
            }
            ExpectedStateTransition actual = new ExpectedStateTransition(service.getName(),
                    oldState, newState);
            logger.atInfo().kv("actual", actual).log("Actual state event");
            if (unexpectedStateTransitions.contains(actual)) {
                unexpectedSeenInOrder.add(actual);
            }
        };

        context.addGlobalStateChangeListener(listener);
        action.run();
        assertionLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        context.removeGlobalStateChangeListener(listener);

        if (!expectedStateTransitions.isEmpty()) {
            logger.atError().kv("expected", expectedStateTransitions).kv("action", actionName)
                    .log("Fail to see state events");
            fail("Didn't see all expected state transitions for " + actionName);
        }

        if (!unexpectedSeenInOrder.isEmpty()) {
            logger.atError().kv("unexpected", unexpectedSeenInOrder).kv("action", actionName)
                    .log("Saw unexpected state events");
            fail("Saw unexpected state transitions for " + actionName);
        }
        logger.atWarn().log("End of " + actionName);
    }

    @SuppressWarnings({"PMD.CloseResource"})
    private static void testStateTransitionsInNoOrder(long timeoutSeconds, Kernel kernel, Crashable action,
                                                      String actionName,
                                                      Set<ExpectedStateTransition> expectedStateTransitions)
            throws Throwable {
        Context context = kernel.getContext();
        CountDownLatch assertionLatch = new CountDownLatch(expectedStateTransitions.size());

        GlobalStateChangeListener listener = (GreengrassService service, State oldState, State newState) -> {
            for (ExpectedStateTransition est : expectedStateTransitions) {
                if(service.getName().equals(est.serviceName) && oldState == est.was && newState == est.current) {
                    logger.atInfo().kv("expected", est).log("Just saw expected state transition");
                    assertionLatch.countDown();
                } else {
                    ExpectedStateTransition other = new ExpectedStateTransition(service.getName(),
                            oldState, newState);
                    logger.atInfo().kv("other", other).log("Saw other state transition event");
                }
            }
        };
        context.addGlobalStateChangeListener(listener);
        action.run();
        assertionLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        context.removeGlobalStateChangeListener(listener);

        if(assertionLatch.getCount() != 0) {
            fail("Did not see all the expected state transitions for action " + actionName);
        }
    }

    @Test
    void GIVEN_hard_dependency_WHEN_dependency_goes_through_lifecycle_events_THEN_customer_app_is_impacted()
            throws Throwable {
        // setup
        kernel = new Kernel();
        URL configFile = ServiceDependencyLifecycleTest.class.getResource("service_with_hard_dependency.yaml");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFile);

        // WHEN_kernel_launch_THEN_customer_app_starts_after_hard_dependency_is_running
        LinkedList<ExpectedStateTransition> expectedDuringLaunch = new LinkedList<>(
                Arrays.asList(
                        new ExpectedStateTransition(HardDependency, State.INSTALLED, State.STARTING),
                        new ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new ExpectedStateTransition(CustomerApp, State.INSTALLED, State.STARTING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING),
                        new ExpectedStateTransition("main", State.STOPPING, State.FINISHED)));

        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, kernel::launch, "kernel launched", expectedDuringLaunch, Collections.emptySet());

        // WHEN_dependency_removed_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepRemoved = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(HardDependency, State.RUNNING, State.STOPPING)));
        Set<ExpectedStateTransition> unexpectedDepRemoved = new HashSet<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));

        Map<String, Object> configRemoveDep = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp,
                            DEFAULT_NUCLEUS_COMPONENT_NAME));
                }});
                put(CustomerApp, new HashMap<String, Object>() {{
                    putAll(kernel.findServiceTopic(CustomerApp).toPOJO());
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                }});
                put(DEFAULT_NUCLEUS_COMPONENT_NAME, new HashMap<String, Object>() {{
                    putAll(kernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME).toPOJO());
                }});
            }});
        }};

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("removeHardDep");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(TEST_ROUTINE_LONG_TIMEOUT, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc1), configRemoveDep).get(60,
                        TimeUnit.SECONDS),
                "dependency removed", expectedDepRemoved, unexpectedDepRemoved);


        // WHEN_dependency_added_THEN_customer_app_restarts
        LinkedList<ExpectedStateTransition> expectedDepAdded = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));

        Map<String, Object> configAddDep = ConfigPlatformResolver.resolvePlatformMap(configFile);

        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("addHardDep");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(60, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc2), configAddDep).get(10, TimeUnit.SECONDS),
                "dependency added", expectedDepAdded, Collections.emptySet());


        // WHEN_dependency_errored_THEN_customer_app_restarts
        LinkedList<ExpectedStateTransition> expectedDuringDepError = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(HardDependency, State.RUNNING, State.ERRORED),
                        new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(HardDependency).serviceErrored("mock dependency error"),
                "dependency errored", expectedDuringDepError, Collections.emptySet());


        // WHEN_dependency_stops_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepFinish = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(HardDependency, State.STOPPING, State.FINISHED)));
        Set<ExpectedStateTransition> unexpectedDepFinish = new HashSet<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));
        testRoutine(TEST_ROUTINE_MEDIUM_TIMEOUT, kernel, () -> kernel.locate(HardDependency).requestStop(), "dependency stop", expectedDepFinish,
                unexpectedDepFinish);


        // WHEN_dependency_restarts_THEN_customer_app_restarts
        LinkedList<ExpectedStateTransition> expectedDepRestart = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(HardDependency).requestRestart(), "dependency restart",
                expectedDepRestart, Collections.emptySet());


        // WHEN_dependency_reinstalled_THEN_customer_app_restarts
        LinkedList<ExpectedStateTransition> expectedDepReinstall = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(HardDependency, State.NEW, State.INSTALLED),
                        new ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(HardDependency).requestReinstall(), "dependency reinstall",
                expectedDepReinstall, Collections.emptySet());


        // WHEN_kernel_shutdown_THEN_hard_dependency_waits_for_customer_app_to_close
        LinkedList<ExpectedStateTransition> expectedDuringShutdown = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED),
                        new ExpectedStateTransition(HardDependency, State.STOPPING, State.FINISHED)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, kernel::shutdown, "kernel shutdown", expectedDuringShutdown, Collections.emptySet());
    }

    @Test
    void GIVEN_soft_dependency_WHEN_dependency_goes_through_lifecycle_events_THEN_customer_app_is_not_impacted()
            throws Throwable {
        // setup
        kernel = new Kernel();
        URL configFile = ServiceDependencyLifecycleTest.class.getResource("service_with_soft_dependency.yaml");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFile);

        Set<ExpectedStateTransition> unexpectedDuringAllSoftDepChange = new HashSet<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));

        HashSet<ExpectedStateTransition> expectedStateTransitions = new HashSet<>(
          Arrays.asList(new ExpectedStateTransition(CustomerApp, State.NEW, State.INSTALLED),
                  new ExpectedStateTransition(CustomerApp, State.INSTALLED, State.STARTING),
                  new ExpectedStateTransition(SoftDependency, State.INSTALLED, State.STARTING),
                  new ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING),
                  new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));

        testStateTransitionsInNoOrder(TEST_ROUTINE_SHORT_TIMEOUT, kernel, kernel::launch, "kernel launch", expectedStateTransitions);

        // WHEN_dependency_removed_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepRemoved = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.RUNNING, State.STOPPING)));

        Map<String, Object> configRemoveDep = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp,
                            DEFAULT_NUCLEUS_COMPONENT_NAME));
                }});
                put(CustomerApp, new HashMap<String, Object>() {{
                    putAll(kernel.findServiceTopic(CustomerApp).toPOJO());
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                }});
                put(DEFAULT_NUCLEUS_COMPONENT_NAME, new HashMap<String, Object>() {{
                    putAll(kernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME).toPOJO());
                }});
            }});
        }};

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("removeSoftDep");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc1), configRemoveDep).get(10, TimeUnit.SECONDS),
                "dependency removed", expectedDepRemoved, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_added_THEN_customer_app_restarts
        LinkedList<ExpectedStateTransition> expectedDepAdded = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));

        Map<String, Object> configAddDep = ConfigPlatformResolver.resolvePlatformMap(configFile);

        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("addSoftDep");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(TEST_ROUTINE_MEDIUM_TIMEOUT, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc2), configAddDep).get(15, TimeUnit.SECONDS),
                "dependency added", expectedDepAdded, Collections.emptySet());


        // WHEN_dependency_errored_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDuringDepError = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.RUNNING, State.ERRORED),
                        new ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_MEDIUM_TIMEOUT, kernel, () -> kernel.locate(SoftDependency).serviceErrored("mock dependency error"),
                "dependency errored", expectedDuringDepError, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_stops_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepFinish = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.STOPPING, State.FINISHED)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(SoftDependency).requestStop(), "dependency stop", expectedDepFinish,
                unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_restarts_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepRestart = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(SoftDependency).requestRestart(), "dependency restart",
                expectedDepRestart, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_reinstalled_THEN_customer_app_stays_running
        LinkedList<ExpectedStateTransition> expectedDepReinstall = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.NEW, State.INSTALLED),
                        new ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.locate(SoftDependency).requestReinstall(), "dependency reinstall",
                expectedDepReinstall, unexpectedDuringAllSoftDepChange);


        // WHEN_kernel_shutdown_THEN_soft_dependency_does_not_wait_for_customer_app_to_close
        LinkedList<ExpectedStateTransition> expectedDuringShutdown = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition(SoftDependency, State.STOPPING, State.FINISHED),
                        new ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));
        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel, () -> kernel.shutdown(60), "kernel shutdown", expectedDuringShutdown,
                Collections.emptySet());
    }

    @Test
    void WHEN_dependency_type_changes_with_no_other_updates_THEN_customer_app_should_not_restart() throws Throwable {
        // Assuming no other changes in customer app and dependency service

        String Dependency = SoftDependency;
        kernel = new Kernel();
        URL configFile = ServiceDependencyLifecycleTest.class.getResource("service_with_soft_dependency.yaml");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFile);
        kernel.launch();
        assertThat(kernel.locate("main")::getState, eventuallyEval(is(State.FINISHED)));

        // The test below assumes SoftDependency is already running and checks against RUNNING->STOPPING and
        // STARTING->RUNNING. But I have seen cases where it hasn't get to the initial RUNNING state yet. So we need to
        // wait for SoftDependency to be RUNNING first.
        assertThat(kernel.locate("SoftDependency")::getState, eventuallyEval(is(State.RUNNING)));

        List<ExpectedStateTransition> stateTransitions = Arrays
                .asList(new ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING));


        Map<String, Object> depTypeSoftToHard = ConfigPlatformResolver.resolvePlatformMap(configFile);

        ((Map) ((Map) depTypeSoftToHard.get(SERVICES_NAMESPACE_TOPIC)).get(CustomerApp))
                .put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(Dependency + ":" + DependencyType.HARD));

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("typeSoftToHard");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc2), depTypeSoftToHard).get(10, TimeUnit.SECONDS),
                "dependency type changes from soft to hard", new LinkedList<>(), new HashSet<>(stateTransitions));


        Map<String, Object> depTypeHardToSoft = ConfigPlatformResolver.resolvePlatformMap(configFile);
        ((Map) ((Map) depTypeHardToSoft.get(SERVICES_NAMESPACE_TOPIC)).get(CustomerApp))
                .put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(Dependency + ":" + DependencyType.SOFT));

        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("typeHardToSoft");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(TEST_ROUTINE_SHORT_TIMEOUT, kernel,
                () -> configMerger.mergeInNewConfig(createMockDeployment(doc1), depTypeHardToSoft).get(10, TimeUnit.SECONDS),
                "dependency type changes from hard to soft", new LinkedList<>(), new HashSet<>(stateTransitions));
    }

    private Deployment createMockDeployment(DeploymentDocument doc) {
        when(doc.getComponentUpdatePolicy()).thenReturn(new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS));
        when(doc.getConfigurationValidationPolicy())
                .thenReturn(DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(20).build());
        Deployment deployment = mock(Deployment.class);
        doReturn(doc).when(deployment).getDeploymentDocumentObj();
        return deployment;
    }
}
