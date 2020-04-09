/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.Validator;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class EvergreenServiceTest extends EGServiceTestUtil {
    private static final String STATE_TOPIC_NAME = "_State";
    private static final int NUM = 100;

    private EvergreenService evergreenService;

    @Captor
    private ArgumentCaptor<Validator> validatorArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        evergreenService = new EvergreenService(initializeMockedConfig());
    }

    @Test
    void GIVEN_a_config_WHEN_constructor_is_called_THEN_service_is_initialized() {
        // GIVEN
        // provided in the beforeEach

        // WHEN
        // provided in the beforeEach

        // THEN
        // verify config
        Assertions.assertSame(config, evergreenService.config);
        Mockito.verify(config).createLeafChild(STATE_TOPIC_NAME);

        // verify stateTopic
        Mockito.verify(stateTopic).setParentNeedsToKnow(false);
        Mockito.verify(stateTopic).setValue(State.NEW);
        Mockito.verify(stateTopic).validate(validatorArgumentCaptor.capture());
        Mockito.verifyNoMoreInteractions(stateTopic);

        // verify validator
        Validator validator = validatorArgumentCaptor.getValue();
        State returnedState = (State) validator.validate(State.NEW, null);
        Assertions.assertSame(State.NEW, returnedState);
    }

    @Test
    void GIVEN_a_new_state_WHEN_getState_THEN_return_the_new_state() {
        Mockito.when(stateTopic.getOnce()).thenReturn(State.NEW);

        Assertions.assertSame(State.NEW, evergreenService.getState());

        Mockito.verify(stateTopic).getOnce();
    }

    private class AwesomeService extends EvergreenService {
        @Inject
        private CountDownLatch cd;

        @Inject
        public AwesomeService(Context context) {
            super(Topics.errorNode(context, "AwesomeService", "testing"));
        }

        @Override
        public void startup() {
            new Thread(() -> {
                for (int i = 0; i < NUM; i++) {
                    reportState(State.RUNNING);
                    reportState(State.STOPPING);
                }

                cd.countDown();
            }).start();
        }
    }

    static int n = 0; // Easy way to pass n without creating a mutable integer.
    @Test
    void GIVEN_a_service_WHEN_reportState_THEN_all_state_changes_are_notified() throws InterruptedException {
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(2);
        ExecutorService cachedPool = Executors.newCachedThreadPool();
        CountDownLatch cd = new CountDownLatch(1);

        Context context = new Context();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, cachedPool);
        context.put(ExecutorService.class, cachedPool);
        context.put(ThreadPoolExecutor.class, ses);
        context.put(CountDownLatch.class, cd);
        context.addGlobalStateChangeListener((service, oldState, newState, latest) -> {
            n++;
        });

        context.get(AwesomeService.class).requestStart();
        cd.await();
        context.get(AwesomeService.class).requestStop();

        // Wait a little to receive all state changes.
        Thread.sleep(10_000);

        // In addition to the states that AwesomeService reports by itself in its loop, there is also an INSTALLED and
        // FINISHED state change. Therefore, we need to add 2.
        assertEquals(NUM * 2 + 2, n);
    }
}
