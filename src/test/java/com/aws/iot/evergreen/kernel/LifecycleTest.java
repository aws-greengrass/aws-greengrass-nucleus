package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import lombok.Setter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class LifecycleTest {

    @Mock
    protected EvergreenService evergreenService;

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

    @BeforeAll
    static void setupProperty() {
        System.setProperty("log.store", "CONSOLE");
    }

    @BeforeEach
    void setupContext() {
        context = new Context();
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(4);
        ExecutorService executorService = Executors.newCachedThreadPool();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, executorService);
        context.put(ExecutorService.class, executorService);
        context.put(ThreadPoolExecutor.class, ses);
        context.put(Clock.class, Clock.systemUTC());

        Topics rootConfig = new Configuration(context).getRoot();
        config = rootConfig.createInteriorChild(EvergreenService.SERVICES_NAMESPACE_TOPIC)
                .createInteriorChild("MockService");
        try (InputStream inputStream = new ByteArrayInputStream(BLANK_CONFIG_YAML_WITH_TIMEOUT.getBytes())) {
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));
        } catch (IOException e) {
            fail(e);
        }

        lenient().when(evergreenService.getConfig()).thenReturn(config);
        lenient().when(evergreenService.getContext()).thenReturn(context);
        lenient().when(evergreenService.dependencyReady()).thenReturn(true);
    }

    @AfterEach
    void stop() {
        if (lifecycle != null) {
            lifecycle.setClosed(true);
        }
    }

    @Test
    public void GIVEN_state_new_WHEN_requestStart_called_THEN_install_invoked() throws InterruptedException {
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.NEW);

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        Mockito.verify(evergreenService, Mockito.timeout(100)).install();
        Mockito.verify(evergreenService, Mockito.timeout(100)).startup();
        assertEquals(State.STARTING, lifecycle.getState());
    }

    @Test
    public void GIVEN_state_new_WHEN_install_timeout_THEN_service_errored() throws InterruptedException {
        //GIVEN
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.NEW);

        AtomicBoolean installInterrupted = new AtomicBoolean(false);
        Mockito.doAnswer((mock) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                installInterrupted.set(true);
            }
            return null;
        }).when(evergreenService).install();

        CountDownLatch errorHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            errorHandleLatch.countDown();
            return null;
        }).when(evergreenService).handleError();

        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.ERRORED);
            return null;
        }).when(evergreenService).serviceErrored(Mockito.anyString());

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        boolean errorHandled = errorHandleLatch.await(DEFAULT_TEST_TIMEOUT + 1, TimeUnit.SECONDS);

        // THEN
        assertTrue(errorHandled);
        assertThat(installInterrupted::get, eventuallyEval(is(true)));
    }

    @Test
    public void GIVEN_state_installed_WHEN_startup_timeout_THEN_service_errored() throws InterruptedException {
        // GIVEN
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.INSTALLED);

        AtomicBoolean startupInterrupted = new AtomicBoolean(false);
        Mockito.doAnswer((mock) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.set(true);
            }
            return null;
        }).when(evergreenService).startup();

        CountDownLatch errorHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            errorHandleLatch.countDown();
            return null;
        }).when(evergreenService).handleError();

        CountDownLatch shutdownHandleLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            if (errorHandleLatch.getCount() == 0) {
                shutdownHandleLatch.countDown();
            }
            return null;
        }).when(evergreenService).shutdown();

        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.ERRORED);
            return null;
        }).when(evergreenService).serviceErrored(Mockito.anyString());

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        boolean shutdownCalled = shutdownHandleLatch.await(DEFAULT_TEST_TIMEOUT + 1, TimeUnit.SECONDS);

        // THEN
        assertTrue(shutdownCalled);
        assertThat(startupInterrupted::get, eventuallyEval(is(true)));
    }

    @Test
    public void GIVEN_state_running_WHEN_requestStop_THEN_shutdown_called() throws InterruptedException {
        // GIVEN
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.INSTALLED);

        AtomicBoolean startupInterrupted = new AtomicBoolean(false);
        Mockito.doAnswer((mock) -> {
            lifecycle.reportState(State.RUNNING);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.set(true);
            }
            return null;
        }).when(evergreenService).startup();


        CountDownLatch shutdownCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            shutdownCalledLatch.countDown();
            return null;
        }).when(evergreenService).shutdown();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // WHEN
        lifecycle.requestStop();
        shutdownCalledLatch.await(1, TimeUnit.SECONDS);

        // THEN
        Mockito.verify(evergreenService).startup();
        Mockito.verify(evergreenService).shutdown();
        assertThat(startupInterrupted::get, eventuallyEval(is(true)));
        assertThat(lifecycle::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Test
    public void GIVEN_state_install_WHEN_requestStop_THEN_shutdown_called() throws InterruptedException {
        // GIVEN
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.INSTALLED);

        AtomicBoolean startupInterrupted = new AtomicBoolean(false);
        Mockito.doAnswer((mock) -> {
            // not report RUNNING here
            lifecycle.requestStop();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                startupInterrupted.set(true);
            }
            return null;
        }).when(evergreenService).startup();

        CountDownLatch shutdownCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            shutdownCalledLatch.countDown();
            return null;
        }).when(evergreenService).shutdown();

        // WHEN
        lifecycle.initLifecycleThread();
        lifecycle.requestStart();

        // THEN
        shutdownCalledLatch.await(1, TimeUnit.SECONDS);
        Mockito.verify(evergreenService).startup();
        Mockito.verify(evergreenService).shutdown();

        assertThat(startupInterrupted::get, eventuallyEval(is(true)));
        assertThat(lifecycle::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Test
    void GIVEN_a_service_WHEN_reportState_THEN_all_state_changes_are_notified() throws InterruptedException {
        // set lifecycle thread with min priority
        ExecutorService executorService = Executors.newCachedThreadPool(new MinPriorityThreadFactory());
        context.put(Executor.class, executorService);
        context.put(ExecutorService.class, executorService);

        // set evergreenService startup() thread with max priority
        Mockito.doAnswer((mock) -> {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
            lifecycle.reportState(State.RUNNING);
            lifecycle.reportState(State.ERRORED);
            return null;
        }).when(evergreenService).startup();

        // GIVEN
        lifecycle = new Lifecycle(evergreenService, logger);
        initLifecycleState(lifecycle, State.INSTALLED);

        CountDownLatch processed = new CountDownLatch(1);
        Mockito.doAnswer((mock) -> {
            processed.countDown();
            // sleep to block state transition
            Thread.sleep(2000);
            return null;
        }).when(evergreenService).handleError();

        AtomicInteger runningReported  = new AtomicInteger(0);
        AtomicInteger errorReported  = new AtomicInteger(0);

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
    }

    @Test
    void GIVEN_state_running_WHEN_errored_3_times_THEN_broken() throws InterruptedException {
        lifecycle = new Lifecycle(evergreenService, logger);
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
        }).when(evergreenService).startup();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        assertTrue(reachedRunning1.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 1st error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning2.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 2nd error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning3.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 3rd error
        lifecycle.reportState(State.ERRORED);
        assertThat(lifecycle::getState, eventuallyEval(is(State.BROKEN)));
    }

    @Test
    void GIVEN_state_running_WHEN_errored_long_time_in_between_THEN_not_broken() throws InterruptedException {
        Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        context.put(Clock.class, clock);

        lifecycle = new Lifecycle(evergreenService, logger);
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
        }).when(evergreenService).startup();

        lifecycle.initLifecycleThread();
        lifecycle.requestStart();
        assertTrue(reachedRunning1.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 1st error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning2.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 2nd error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning3.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));

        // Report 3rd error, but after a while
        clock = Clock.offset(clock, Duration.ofHours(1).plusMillis(1));
        context.put(Clock.class, clock);
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning4.await(5, TimeUnit.SECONDS));
        assertThat(lifecycle::getState, eventuallyEval(is(State.RUNNING)));
    }

    private class MinPriorityThreadFactory implements ThreadFactory {
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
                .createInteriorChild(EvergreenService.SERVICES_NAMESPACE_TOPIC);
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
        assertTrue(serviceStarted.await(100, TimeUnit.MILLISECONDS));
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

    private class TestService extends EvergreenService {
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
