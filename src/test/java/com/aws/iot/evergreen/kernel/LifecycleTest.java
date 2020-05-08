package com.aws.iot.evergreen.kernel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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

        config = new Topics(context, "MockService", null);
        try (InputStream inputStream = new ByteArrayInputStream(BLANK_CONFIG_YAML_WITH_TIMEOUT.getBytes())) {
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));
        } catch (IOException e) {
            fail(e);
        }

        Mockito.when(evergreenService.getConfig()).thenReturn(config);
        Mockito.when(evergreenService.getContext()).thenReturn(context);
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
        // sleep to let error handle finished
        Thread.sleep(100);
        assertTrue(installInterrupted.get());
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
        // sleep to let shutdown finished
        Thread.sleep(100);
        assertTrue(startupInterrupted.get());
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

        Thread.sleep(100);
        assertEquals(State.RUNNING, lifecycle.getState());

        // WHEN
        lifecycle.requestStop();
        shutdownCalledLatch.await(1, TimeUnit.SECONDS);

        // THEN
        Mockito.verify(evergreenService).startup();
        Mockito.verify(evergreenService).shutdown();
        Thread.sleep(100);
        assertTrue(startupInterrupted.get());
        assertEquals(State.FINISHED, lifecycle.getState());
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

        Thread.sleep(100);
        assertTrue(startupInterrupted.get());
        assertEquals(State.FINISHED, lifecycle.getState());
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
        Thread.sleep(1000); // Lifecycle thread needs some time to process the state transition
        assertEquals(State.RUNNING, lifecycle.getState());

        // Report 1st error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning2.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000); // Lifecycle thread needs some time to process the state transition
        assertEquals(State.RUNNING, lifecycle.getState());  // Expect to recover

        // Report 2nd error
        lifecycle.reportState(State.ERRORED);
        assertTrue(reachedRunning3.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000); // Lifecycle thread needs some time to process the state transition
        assertEquals(State.RUNNING, lifecycle.getState());  // Expect to recover

        // Report 3rd error
        lifecycle.reportState(State.ERRORED);
        Thread.sleep(1000);
        assertEquals(State.BROKEN, lifecycle.getState());
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
}
