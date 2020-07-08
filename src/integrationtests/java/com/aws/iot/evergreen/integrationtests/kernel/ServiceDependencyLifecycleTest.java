/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.Crashable;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

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

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class ServiceDependencyLifecycleTest {
    private static final String CustomerApp = "CustomerApp";
    private static final String HardDependency = "HardDependency";
    private static final String SoftDependency = "SoftDependency";
    private static final Logger logger = LogManager.getLogger(ServiceDependencyLifecycleTest.class);

    private Kernel kernel;

    @AfterEach
    void teardown() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @SuppressWarnings({"PMD.LooseCoupling", "PMD.CloseResource"})
    private static void testRoutine(long timeoutSeconds, Kernel kernel, Crashable action, String actionName, LinkedList<KernelTest.ExpectedStateTransition> expectedStateTransitions, Set<KernelTest.ExpectedStateTransition> unexpectedStateTransitions) throws Throwable {
        Context context = kernel.getContext();
        CountDownLatch assertionLatch = new CountDownLatch(1);
        List<KernelTest.ExpectedStateTransition> unexpectedSeenInOrder = new LinkedList<>();

        GlobalStateChangeListener listener = (EvergreenService service, State oldState, State newState) -> {
            if (!expectedStateTransitions.isEmpty()) {
                KernelTest.ExpectedStateTransition expected = expectedStateTransitions.peek();

                if (service.getName().equals(expected.serviceName) && oldState.equals(expected.was) && newState
                        .equals(expected.current)) {
                    logger.atWarn().kv("expected", expected).log("Just saw expected state event for service");
                    expectedStateTransitions.pollFirst();
                }
                if (expectedStateTransitions.isEmpty()) {
                    assertionLatch.countDown();
                }
            }
            KernelTest.ExpectedStateTransition actual = new KernelTest.ExpectedStateTransition(service.getName(),
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
            logger.atError().kv("expected", expectedStateTransitions).kv("action", actionName).log(
                    "Fail to see state events");
            fail("Didn't see all expected state transitions for "+actionName);
        }

        if (!unexpectedSeenInOrder.isEmpty()) {
            logger.atError().kv("unexpected", unexpectedSeenInOrder).kv("action", actionName).log(
                    "Saw unexpected state events");
            fail("Saw unexpected state transitions for "+actionName);
        }
        logger.atWarn().log("End of " + actionName);
    }

    @Test
    void GIVEN_hard_dependency_WHEN_dependency_goes_through_lifecycle_events_THEN_customer_app_is_impacted() throws Throwable {
        // setup
        kernel = new Kernel()
                .parseArgs("-i", ServiceDependencyLifecycleTest.class.getResource("service_with_hard_dependency.yaml")
                        .toString());

        // WHEN_kernel_launch_THEN_customer_app_starts_after_hard_dependency_is_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringLaunch = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.NEW, State.INSTALLED),
                        new KernelTest.ExpectedStateTransition(HardDependency, State.NEW, State.INSTALLED),
                        new KernelTest.ExpectedStateTransition(HardDependency, State.INSTALLED, State.STARTING),
                        new KernelTest.ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.INSTALLED, State.STARTING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING),
                        new KernelTest.ExpectedStateTransition("main", State.STOPPING, State.FINISHED)));
        testRoutine(15, kernel, kernel::launch, "kernel launch", expectedDuringLaunch, Collections.emptySet());


        // WHEN_dependency_removed_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepRemoved = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(HardDependency, State.RUNNING, State.STOPPING)));
        Set<KernelTest.ExpectedStateTransition> unexpectedDepRemoved = new HashSet<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));

        Map<Object, Object> configRemoveDep = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("removeHardDep");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(15, kernel, () -> configMerger.mergeInNewConfig(doc1, configRemoveDep).get(10, TimeUnit.SECONDS),
                "dependency removed", expectedDepRemoved, unexpectedDepRemoved);


        // WHEN_dependency_added_THEN_customer_app_restarts
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepAdded = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));

        Map<Object, Object> configAddDep = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(HardDependency + ":" + DependencyType.HARD));
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
                put(HardDependency, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("addHardDep");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(60, kernel, () -> configMerger.mergeInNewConfig(doc2, configAddDep).get(10,
                TimeUnit.SECONDS),
                "dependency added", expectedDepAdded, Collections.emptySet());


        // WHEN_dependency_errored_THEN_customer_app_restarts
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringDepError = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(HardDependency, State.RUNNING, State.ERRORED),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(HardDependency)
                .serviceErrored("mock dependency error"), "dependency errored", expectedDuringDepError, Collections
                .emptySet());


        // WHEN_dependency_stops_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepFinish = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(HardDependency, State.STOPPING, State.FINISHED)));
        Set<KernelTest.ExpectedStateTransition> unexpectedDepFinish = new HashSet<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));
        testRoutine(15, kernel, () -> kernel.locate(HardDependency)
                .requestStop(), "dependency stop", expectedDepFinish, unexpectedDepFinish);


        // WHEN_dependency_restarts_THEN_customer_app_restarts
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepRestart = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(HardDependency)
                .requestRestart(), "dependency restart", expectedDepRestart, Collections.emptySet());


        // WHEN_dependency_reinstalled_THEN_customer_app_restarts
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepReinstall = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(HardDependency, State.NEW, State.INSTALLED),
                        new KernelTest.ExpectedStateTransition(HardDependency, State.STARTING, State.RUNNING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(HardDependency)
                .requestReinstall(), "dependency reinstall", expectedDepReinstall, Collections.emptySet());


        // WHEN_kernel_shutdown_THEN_hard_dependency_waits_for_customer_app_to_close
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringShutdown = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED),
                        new KernelTest.ExpectedStateTransition(HardDependency, State.STOPPING, State.FINISHED)));
        testRoutine(15, kernel, kernel::shutdown, "kernel shutdown", expectedDuringShutdown, Collections.emptySet());
    }

    @Test
    void GIVEN_soft_dependency_WHEN_dependency_goes_through_lifecycle_events_THEN_customer_app_is_not_impacted() throws Throwable {
        // setup
        kernel = new Kernel()
                .parseArgs("-i", ServiceDependencyLifecycleTest.class.getResource("service_with_soft_dependency.yaml")
                        .toString());

        Set<KernelTest.ExpectedStateTransition> unexpectedDuringAllSoftDepChange = new HashSet<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));

        // WHEN_kernel_launch_THEN_customer_app_starts_independently_from_soft_dependency
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringLaunch = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.NEW, State.INSTALLED),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.INSTALLED, State.STARTING),
                        new KernelTest.ExpectedStateTransition(SoftDependency, State.INSTALLED, State.STARTING),
                        new KernelTest.ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, kernel::launch, "kernel launch", expectedDuringLaunch, Collections.emptySet());


        // WHEN_dependency_removed_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepRemoved = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.RUNNING, State.STOPPING)));

        Map<Object, Object> configRemoveDep = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("removeSoftDep");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(15, kernel, () -> configMerger.mergeInNewConfig(doc1, configRemoveDep).get(10, TimeUnit.SECONDS),
                "dependency removed", expectedDepRemoved, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_added_THEN_customer_app_restarts
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepAdded = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING)));

        Map<Object, Object> configAddDep = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(SoftDependency + ":" + DependencyType.SOFT));
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
                put(SoftDependency, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("addSoftDep");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(15, kernel, () -> configMerger.mergeInNewConfig(doc2, configAddDep).get(10,
                TimeUnit.SECONDS),
                "dependency added", expectedDepAdded, Collections.emptySet());


        // WHEN_dependency_errored_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringDepError = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.RUNNING, State.ERRORED),
                        new KernelTest.ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(SoftDependency)
                .serviceErrored("mock dependency error"), "dependency errored", expectedDuringDepError, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_stops_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepFinish = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.STOPPING, State.FINISHED)));
        testRoutine(15, kernel, () -> kernel.locate(SoftDependency)
                .requestStop(), "dependency stop", expectedDepFinish, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_restarts_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepRestart = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(SoftDependency)
                .requestRestart(), "dependency restart", expectedDepRestart, unexpectedDuringAllSoftDepChange);


        // WHEN_dependency_reinstalled_THEN_customer_app_stays_running
        LinkedList<KernelTest.ExpectedStateTransition> expectedDepReinstall = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.NEW, State.INSTALLED),
                        new KernelTest.ExpectedStateTransition(SoftDependency, State.STARTING, State.RUNNING)));
        testRoutine(15, kernel, () -> kernel.locate(SoftDependency)
                .requestReinstall(), "dependency reinstall", expectedDepReinstall, unexpectedDuringAllSoftDepChange);


        // WHEN_kernel_shutdown_THEN_soft_dependency_does_not_wait_for_customer_app_to_close
        LinkedList<KernelTest.ExpectedStateTransition> expectedDuringShutdown = new LinkedList<>(Arrays
                .asList(new KernelTest.ExpectedStateTransition(SoftDependency, State.STOPPING, State.FINISHED),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STOPPING, State.FINISHED)));
        testRoutine(15, kernel, () -> kernel.shutdown(60), "kernel shutdown", expectedDuringShutdown,
                Collections.emptySet());
    }

    @Test
    void WHEN_dependency_type_changes_with_no_other_updates_THEN_customer_app_should_not_restart() throws Throwable {
        // Assuming no other changes in customer app and dependency service

        String Dependency = SoftDependency;
        kernel = new Kernel()
                .parseArgs("-i", ServiceDependencyLifecycleTest.class.getResource("service_with_soft_dependency.yaml")
                        .toString()).launch();
        assertThat(kernel.locate("main")::getState, eventuallyEval(is(State.FINISHED)));

        // The test below assumes SoftDependency is already running and checks against RUNNING->STOPPING and
        // STARTING->RUNNING. But I have seen cases where it hasn't get to the initial RUNNING state yet. So we need to
        // wait for SoftDependency to be RUNNING first.
        assertThat(kernel.locate("SoftDependency")::getState, eventuallyEval(is(State.RUNNING)));

        List<KernelTest.ExpectedStateTransition> stateTransitions = Arrays
                .asList(new KernelTest.ExpectedStateTransition(CustomerApp, State.RUNNING, State.STOPPING),
                        new KernelTest.ExpectedStateTransition(CustomerApp, State.STARTING, State.RUNNING));


        Map<Object, Object> depTypeSoftToHard = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(Dependency + ":" + DependencyType.HARD));
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
                put(Dependency, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentConfigMerger configMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        DeploymentDocument doc2 = mock(DeploymentDocument.class);
        when(doc2.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc2.getDeploymentId()).thenReturn("typeSoftToHard");
        when(doc2.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(15, kernel, () -> configMerger.mergeInNewConfig(doc2, depTypeSoftToHard).get(10,
                TimeUnit.SECONDS), "dependency type changes from soft to hard", new LinkedList<>(),
                new HashSet<>(stateTransitions));


        Map<Object, Object> depTypeHardToSoft = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(CustomerApp));
                }});
                put(CustomerApp, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList(Dependency + ":" + DependencyType.SOFT));
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
                put(Dependency, new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Collections.emptyList());
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "while true; do sleep 1000; done");
                        }});
                    }});
                }});
            }});
        }};

        DeploymentDocument doc1 = mock(DeploymentDocument.class);
        when(doc1.getTimestamp()).thenReturn(System.currentTimeMillis());
        when(doc1.getDeploymentId()).thenReturn("typeHardToSoft");
        when(doc1.getFailureHandlingPolicy()).thenReturn(FailureHandlingPolicy.DO_NOTHING);

        testRoutine(15, kernel, () -> configMerger.mergeInNewConfig(doc1, depTypeHardToSoft)
                        .get(10, TimeUnit.SECONDS), "dependency type changes from hard to soft",
                new LinkedList<>(), new HashSet<>(stateTransitions));
    }
}
