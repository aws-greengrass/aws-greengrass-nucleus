/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.AbstractBaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PerformanceReporting.class)
class ServiceConfigMergingTest extends AbstractBaseITCase {
    private Kernel kernel;

    @BeforeEach
    void before(TestInfo testInfo) {
        System.out.println("Running test: " + testInfo.getDisplayName());
        //See transient errors where property does not get set at the time this test runs. Setting it here explicitly
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();
    }

    @AfterEach
    void after() {
        kernel.shutdownNow();
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_change_to_service_THEN_service_restarts_with_new_config()
            throws Throwable {

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState
                    .equals(State.INSTALLED)) {
                mainRestarted.countDown();
            }
        });
        kernel.mergeInNewConfig("id", System.currentTimeMillis(), new HashMap<Object, Object>() {{
            put("services", new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put("setenv", new HashMap<Object, Object>() {{
                        put("HELLO", "redefined");
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
        assertEquals("redefined", kernel.find("services", "main", "setenv", "HELLO").getOnce());
        assertThat((String) kernel.find("services", "main", "lifecycle", "run").getOnce(),
                containsString("echo \"Running main\""));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_change_adding_dependency_THEN_dependent_service_starts_and_service_restarts()
            throws Throwable {
        System.out.println("The root property is: " + System.getProperty("root"));
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        CountDownLatch newServiceStarted = new CountDownLatch(1);

        // Check that new_service starts and then main gets restarted
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service") && newState.equals(State.RUNNING)) {
                newServiceStarted.countDown();
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.getCount() == 0) {
                if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState
                        .equals(State.INSTALLED)) {
                    mainRestarted.countDown();
                }
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(EvergreenService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");
        kernel.mergeInNewConfig("id", System.currentTimeMillis(), new HashMap<Object, Object>() {{
            put("services", new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put("dependencies", serviceList);
                }});

                put("new_service", new HashMap<Object, Object>() {{
                    put("lifecycle", new HashMap<Object, Object>() {{
                        put("run", new HashMap<Object, Object>() {{
                            put("script", "sleep 60");
                        }});
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(newServiceStarted.await(60, TimeUnit.SECONDS));
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_change_adding_nested_dependency_THEN_dependent_services_start_and_service_restarts()
            throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        CountDownLatch newService2Started = new CountDownLatch(1);
        CountDownLatch newServiceStarted = new CountDownLatch(1);

        // Check that new_service2 starts, then new_service, and then main gets restarted
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service2") && newState.equals(State.RUNNING)) {
                newService2Started.countDown();
            }
            if (newService2Started.getCount() == 0) {
                if (service.getName().equals("new_service") && newState.equals(State.RUNNING)) {
                    newServiceStarted.countDown();
                }
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.getCount() == 0) {
                if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState
                        .equals(State.INSTALLED)) {
                    mainRestarted.countDown();
                }
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(EvergreenService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");

        kernel.mergeInNewConfig("id", System.currentTimeMillis(), new HashMap<Object, Object>() {{
            put("services", new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put("dependencies", serviceList);
                }});

                put("new_service", new HashMap<Object, Object>() {{
                    put("lifecycle", new HashMap<Object, Object>() {{
                        put("run", "sleep 60");
                    }});
                    put("dependencies", Arrays.asList("new_service2"));
                }});

                put("new_service2", new HashMap<Object, Object>() {{
                    put("lifecycle", new HashMap<Object, Object>() {{
                        put("run", "sleep 60");
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(newService2Started.await(60, TimeUnit.SECONDS));
        assertTrue(newServiceStarted.await(60, TimeUnit.SECONDS));
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
        assertThat(kernel.orderedDependencies().stream().map(EvergreenService::getName).collect(Collectors.toList()),
                containsInRelativeOrder("new_service2", "new_service", "main"));
    }


    @Test
    void GIVEN_kernel_running_services_WHEN_merge_removes_service_THEN_removed_service_is_closed()
            throws Throwable {
        // GIVEN
        kernel.parseArgs("-i",
                getClass().getResource("long_running_services.yaml").toString());
        kernel.launch();

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (((State) t.getOnce()).isRunning()) {
                mainRunningLatch.countDown();
            }
        });

        //wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS));

        Map<Object, Object> currentConfig = new HashMap<>(kernel.toPOJO());
        Map<String,Map> servicesConfig = (Map<String, Map>) currentConfig.get(EvergreenService.SERVICES_NAMESPACE_TOPIC);
        Iterator<String> itr = servicesConfig.keySet().iterator();

        //removing all services in the current kernel config except sleeperB and main
        while(itr.hasNext()){
            String serviceName = itr.next();
            if(!serviceName.equals("sleeperB") && !serviceName.equals("main")){
                itr.remove();
            }
        }
        List<String> dependencies = new ArrayList((List<String>)servicesConfig.get("main").get("dependencies")) ;
        //removing main's dependency on sleeperA, Now sleeperA is an unused dependency
        dependencies.remove("sleeperA:RUNNING");
        servicesConfig.get("main").put("dependencies",dependencies);
        // updating service B's run
        ((Map) servicesConfig.get("sleeperB").get(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC))
                .put("run","while true; do\n echo sleeperB_running; sleep 10\n done");

        Future<Void> future = kernel.mergeInNewConfig("id", System.currentTimeMillis(), currentConfig);
        AtomicBoolean isSleeperAClosed = new AtomicBoolean(false);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("sleeperA") && newState.isClosable()) {
                isSleeperAClosed.set(true);
            }
        });

        EvergreenService main = EvergreenService.locate(kernel.context, "main");
        EvergreenService sleeperB = EvergreenService.locate(kernel.context, "sleeperB");
        // wait for merge to complete
        future.get(60, TimeUnit.SECONDS);
        //sleeperA should be closed
        assertTrue(isSleeperAClosed.get());
        // main and sleeperB should be running
        assertTrue(State.RUNNING.equals(main.getState()));
        assertTrue(State.RUNNING.equals(sleeperB.getState()));
        // ensuring config value for sleeperA is removed
        assertFalse(kernel.findTopics("services").children.contains("sleeperA"));
        // ensure kernel no longer holds a reference of sleeperA
        assertThrows(ServiceLoadException.class, () ->  EvergreenService.locate(kernel.context, "sleeperA"));
    }
        // TODO: Work on removing dependencies and stopping and then removing unused dependencies
}
