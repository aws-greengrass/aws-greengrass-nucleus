/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import lombok.Setter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class LifecycleTest {

    @Mock
    protected GreengrassService greengrassService;

    private static final String BLANK_CONFIG_YAML_WITH_TIMEOUT =
            "---\n"
            + "lifecycle:\n"
            + "  install:\n"
            + "    timeout: 1\n"
            + "  startup:\n"
            + "    timeout: 1\n"
            + "  shutdown:\n"
            + "    timeout: 1\n";

    private static final Integer DEFAULT_TEST_TIMEOUT = 1;

    private final Logger logger = LogManager.getLogger("test");
    private Context context;
    private Topics config;
    private Lifecycle lifecycle;

    @BeforeEach
    void setupContext() throws IOException {
        context = new Context();
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(4);
        ExecutorService executorService = Executors.newCachedThreadPool();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, executorService);
        context.put(ExecutorService.class, executorService);
        context.put(ThreadPoolExecutor.class, ses);
        context.put(Clock.class, Clock.systemUTC());
        context.put(Kernel.class, mock(Kernel.class));

        Topics rootConfig = new Configuration(context).getRoot();
        config = rootConfig.createInteriorChild(GreengrassService.SERVICES_NAMESPACE_TOPIC)
                .createInteriorChild("MockService");
        try (InputStream inputStream = new ByteArrayInputStream(BLANK_CONFIG_YAML_WITH_TIMEOUT.getBytes())) {
            config.updateFromMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream),
                    new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE));
        }

        lenient().when(greengrassService.getConfig()).thenReturn(config);
        lenient().when(greengrassService.getRuntimeConfig()).thenReturn(config.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC));
        lenient().when(greengrassService.getPrivateConfig()).thenReturn(config.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC));
        lenient().when(greengrassService.getContext()).thenReturn(context);
        lenient().when(greengrassService.dependencyReady()).thenReturn(true);
    }

    @AfterEach
    void stop() throws IOException {
        if (lifecycle != null) {
            lifecycle.setClosed(true);
            lifecycle.requestStop();
        }
        context.get(ScheduledExecutorService.class).shutdownNow();
        context.get(ExecutorService.class).shutdownNow();
        context.close();
    }

    @Test
    public void GIVEN_state_new_WHEN_requestStart_called_THEN_install_invoked() throws InterruptedException {
        lifecycle = new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig());
        initLifecycleState(lifecycle, State.NEW);

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        verify(greengrassService, timeout(1000)).install();
        verify(greengrassService, timeout(1000)).startup();
        assertEquals(State.STARTING, lifecycle.getState());
    }

    @Test
    public void GIVEN_state_new_WHEN_install_timeout_THEN_service_errored() throws InterruptedException {
        //GIVEN
        lifecycle = new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig());
        initLifecycleState(lifecycle, State.NEW);

        CountDownLatch installInterrupted = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                installInterrupted.countDown();
            }
            return null;
        }).when(greengrassService).install();

        CountDownLatch errorHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            errorHandleLatch.countDown();
            return null;
        }).when(greengrassService).handleError();

        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.ERRORED);
            return null;
        }).when(greengrassService).serviceErrored(Mockito.anyString());

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        boolean errorHandled = errorHandleLatch.await(DEFAULT_TEST_TIMEOUT + 1, TimeUnit.SECONDS);

        // THEN
        assertTrue(errorHandled);
        assertThat(installInterrupted.await(1000, TimeUnit.MILLISECONDS), is(true));
    }

    @Test
    public void GIVEN_state_installed_WHEN_startup_timeout_THEN_service_errored() throws InterruptedException {
        // GIVEN
        lifecycle = new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig());
        initLifecycleState(lifecycle, State.INSTALLED);

        CountDownLatch startupInterrupted = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.countDown();
            }
            return null;
        }).when(greengrassService).startup();

        CountDownLatch errorHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            errorHandleLatch.countDown();
            return null;
        }).when(greengrassService).handleError();

        CountDownLatch shutdownHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            if (errorHandleLatch.getCount() == 0) {
                shutdownHandleLatch.countDown();
            }
            return null;
        }).when(greengrassService).shutdown();

        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.ERRORED);
            return null;
        }).when(greengrassService).serviceErrored(Mockito.anyString());

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        boolean shutdownCalled = shutdownHandleLatch.await(DEFAULT_TEST_TIMEOUT + 1, TimeUnit.SECONDS);

        // THEN
        assertTrue(shutdownCalled);
        assertThat(startupInterrupted.await(1000, TimeUnit.MILLISECONDS), is(true));
    }

    @Test
    public void GIVEN_state_running_WHEN_requestStop_THEN_shutdown_called() throws InterruptedException {
        // GIVEN
        lifecycle = spy(new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig()));
        initLifecycleState(lifecycle, State.INSTALLED);

        CountDownLatch startupInterrupted = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.RUNNING);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.countDown();
            }
            return null;
        }).when(greengrassService).startup();


        CountDownLatch shutdownCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            shutdownCalledLatch.countDown();
            return null;
        }).when(greengrassService).shutdown();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        verify(lifecycle, timeout(1000)).setState(any(), eq(State.RUNNING));

        // WHEN
        lifecycle.requestStop();
        assertTrue(shutdownCalledLatch.await(1, TimeUnit.SECONDS));

        // THEN
        verify(greengrassService).startup();
        verify(greengrassService).shutdown();
        assertThat(startupInterrupted.await(1000, TimeUnit.MILLISECONDS), is(true));
        verify(lifecycle, timeout(1000)).setState(any(), eq(State.FINISHED));
    }

    @Test
    public void GIVEN_state_install_WHEN_requestStop_THEN_shutdown_called() throws InterruptedException {
        // GIVEN
        lifecycle = spy(new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig()));
        initLifecycleState(lifecycle, State.INSTALLED);

        CountDownLatch startupInterrupted = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            // not report RUNNING here
            lifecycle.requestStop();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.countDown();
            }
            return null;
        }).when(greengrassService).startup();

        CountDownLatch shutdownCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            shutdownCalledLatch.countDown();
            return null;
        }).when(greengrassService).shutdown();

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        // THEN
        shutdownCalledLatch.await(1, TimeUnit.SECONDS);
        verify(greengrassService).startup();
        verify(greengrassService).shutdown();

        assertThat(startupInterrupted.await(1000, TimeUnit.MILLISECONDS), is(true));
        verify(lifecycle, timeout(1000)).setState(any(), eq(State.FINISHED));
    }

    @Test
    void GIVEN_a_service_WHEN_reportState_THEN_all_state_changes_are_notified() throws InterruptedException {
        // set lifecycle thread with min priority
        ExecutorService executorService = null;
        try {
            executorService = Executors.newCachedThreadPool(new MinPriorityThreadFactory());
            context.put(Executor.class, executorService);
            context.put(ExecutorService.class, executorService);

            // set greengrassService startup() thread with max priority
            Mockito.doAnswer((mock) -> {
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
                lifecycle.reportState(State.RUNNING);
                lifecycle.reportState(State.ERRORED);
                return null;
            }).when(greengrassService).startup();

            // GIVEN
            lifecycle = new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig());
            initLifecycleState(lifecycle, State.INSTALLED);

            CountDownLatch processed = new CountDownLatch(1);
            Mockito.doAnswer((mock) -> {
                processed.countDown();
                // sleep to block state transition
                Thread.sleep(2000);
                return null;
            }).when(greengrassService).handleError();

            AtomicInteger runningReported = new AtomicInteger(0);
            AtomicInteger errorReported = new AtomicInteger(0);

            context.addGlobalStateChangeListener((service, old, newState) -> {
                if (newState.equals(State.RUNNING)) {
                    runningReported.incrementAndGet();
                } else if (newState.equals(State.ERRORED)) {
                    errorReported.incrementAndGet();
                }
            });

            // WHEN
            lifecycle.initLifecycleThread();
            lifecycle.requestStart();

            processed.await(DEFAULT_TEST_TIMEOUT + 1, TimeUnit.SECONDS);

            // THEN
            assertEquals(1, runningReported.get());
            assertEquals(1, errorReported.get());
        } finally {
            if (executorService != null) {
                executorService.shutdownNow();
            }
        }
    }

    @Test
    void GIVEN_state_running_WHEN_errored_3_times_THEN_broken() throws InterruptedException {
        lifecycle = spy(new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig()));
        initLifecycleState(lifecycle, State.NEW);

        CountDownLatch reachedRunning1 = new CountDownLatch(1);
        CountDownLatch reachedRunning2 = new CountDownLatch(1);
        CountDownLatch reachedRunning3 = new CountDownLatch(1);
        Mockito.doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning1.countDown();
            return null;
        }).doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning2.countDown();
            return null;
        }).doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning3.countDown();
            return null;
        }).when(greengrassService).startup();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        assertTrue(reachedRunning1.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(2000)).setState(any(), eq(State.RUNNING));

        // Report 1st error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning2.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(2000).times(2)).setState(any(), eq(State.RUNNING));

        // Report 2nd error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning3.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(2000).times(3)).setState(any(), eq(State.RUNNING));

        // Report 3rd error
        lifecycle.reportState(State.ERRORED);
        verify(lifecycle, timeout(10_000)).setState(any(), eq(State.BROKEN));
    }

    @Test
    void GIVEN_state_running_WHEN_errored_long_time_in_between_THEN_not_broken() throws InterruptedException {
        Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        context.put(Clock.class, clock);

        lifecycle = spy(new Lifecycle(greengrassService, logger, greengrassService.getPrivateConfig()));
        initLifecycleState(lifecycle, State.NEW);

        CountDownLatch reachedRunning1 = new CountDownLatch(1);
        CountDownLatch reachedRunning2 = new CountDownLatch(1);
        CountDownLatch reachedRunning3 = new CountDownLatch(1);
        CountDownLatch reachedRunning4 = new CountDownLatch(1);
        Mockito.doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning1.countDown();
            return null;
        }).doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning2.countDown();
            return null;
        }).doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning3.countDown();
            return null;
        }).doAnswer(mock -> {
            lifecycle.reportState(State.RUNNING);
            reachedRunning4.countDown();
            return null;
        }).when(greengrassService).startup();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        assertTrue(reachedRunning1.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(1000)).setState(any(), eq(State.RUNNING));

        // Report 1st error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning2.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(1000).times(2)).setState(any(), eq(State.RUNNING));

        // Report 2nd error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning3.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(1000).times(3)).setState(any(), eq(State.RUNNING));

        // Report 3rd error, but after a while
        clock = Clock.offset(clock, Duration.ofHours(1).plusMillis(1));
        context.put(Clock.class, clock);
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning4.await(5, TimeUnit.SECONDS));
        verify(lifecycle, timeout(1000).times(4)).setState(any(), eq(State.RUNNING));
    }

    private static class MinPriorityThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    }

    private void initLifecycleState(Lifecycle lf, State initState) {
        Topic stateTopic = lf.getStateTopic();
        stateTopic.withValue(initState);
    }


    @Test
    public void GIVEN_service_starting_WHEN_dependency_errored_THEN_service_restarted() throws Exception {
        Topics serviceRoot = new Configuration(context).getRoot()
                .createInteriorChild(GreengrassService.SERVICES_NAMESPACE_TOPIC);
        Topics testServiceTopics = serviceRoot.createInteriorChild("testService");
        TestService testService = new TestService(testServiceTopics);

        Topics dependencyServiceTopics = serviceRoot.createInteriorChild("dependencyService");
        TestService dependencyService = new TestService(dependencyServiceTopics);

        testService.addOrUpdateDependency(dependencyService, DependencyType.HARD, false);

        CountDownLatch serviceStarted = new CountDownLatch(1);
        testService.setStartupRunnable(
            () -> {
                try {
                    serviceStarted.countDown();
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        );
        dependencyService.setStartupRunnable(
            () -> dependencyService.reportState(State.RUNNING)
        );

        // init lifecycle
        testService.postInject();
        testService.requestStart();
        dependencyService.postInject();
        dependencyService.requestStart();

        // GIVEN service in state STARTING
        assertTrue(serviceStarted.await(1500, TimeUnit.MILLISECONDS));
        assertEquals(State.STARTING, testService.getState());

        CountDownLatch serviceRestarted = new CountDownLatch(2);
        context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (!"testService".equals(service.getName())) {
                return;
            }
            if (State.STARTING.equals(oldState) && State.STOPPING.equals(newState) &&
                    serviceRestarted.getCount() == 2) {
                serviceRestarted.countDown();
                return;
            }

            if (State.INSTALLED.equals(oldState) && State.STARTING.equals(newState) &&
                    serviceRestarted.getCount() == 1) {
                serviceRestarted.countDown();
                return;
            }
        });

        // WHEN dependency errored
        dependencyService.reportState(State.ERRORED);

        // THEN
        assertTrue(serviceRestarted.await(500, TimeUnit.MILLISECONDS));
    }

    private class TestService extends GreengrassService {
        @Setter
        private Runnable startupRunnable = () -> {};

        TestService(Topics topics) {
            super(topics);
        }

        @Override
        public void startup() {
            startupRunnable.run();
        }
    }
}
