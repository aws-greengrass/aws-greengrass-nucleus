package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.Validator;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.util.Log;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvergreenServiceTest {

    public static final String STATE_TOPIC_NAME = "_State";
    private static final String EVERGREEN_SERVICE_FULL_NAME = "EvergreenServiceFullName";

    private EvergreenService evergreenService;

    @Mock
    private Topics config;

    @Mock
    private Topic stateTopic;

    @Mock
    private Context context;

    @Mock
    private Log log;

    @Captor
    private ArgumentCaptor<Validator> validatorArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        Mockito.when(config.createLeafChild(Mockito.any())).thenReturn(stateTopic);

        evergreenService = new EvergreenService(config);
        evergreenService.context = context;
    }

    @Test
    void testConstructor() {
        // GIVEN
        // beforeEach

        // WHEN
        // beforeEach

        // THEN
        // verify config
        Assertions.assertSame(config, evergreenService.config);
        Mockito.verify(config).createLeafChild(STATE_TOPIC_NAME);

        // verify stateTopic
        Mockito.verify(stateTopic).setParentNeedsToKnow(false);
        Mockito.verify(stateTopic).setValue(Long.MAX_VALUE, State.New);
        Mockito.verify(stateTopic).validate(validatorArgumentCaptor.capture());
        Mockito.verify(stateTopic).subscribe(evergreenService);
        Mockito.verifyNoMoreInteractions(stateTopic);

        // verify validator
        Validator validator = validatorArgumentCaptor.getValue();
        State returnedState = (State) validator.validate(State.New, null);
        Assertions.assertSame(State.New, returnedState);
    }

    @Test
    void getState() {
        Mockito.when(stateTopic.getOnce()).thenReturn(State.New);

        Assertions.assertSame(State.New, evergreenService.getState());

        Mockito.verify(stateTopic).getOnce();

    }

    @Test
    void setState() {

        // GIVEN
        State currentState = State.New;
        State newState = State.Installing;

        Mockito.when(stateTopic.getOnce()).thenReturn(currentState);
        Mockito.when(context.getLog()).thenReturn(log);
        Mockito.when(config.getFullName()).thenReturn(EVERGREEN_SERVICE_FULL_NAME);

        // WHEN
        evergreenService.setState(newState);

        // THEN
        InOrder inOrder = Mockito.inOrder(stateTopic, context, log);
        inOrder.verify(stateTopic).getOnce();
        inOrder.verify(context).getLog();
        inOrder.verify(log).note(EVERGREEN_SERVICE_FULL_NAME, currentState, "=>", newState);
        inOrder.verify(stateTopic).setValue(Long.MAX_VALUE, newState);
        inOrder.verify(context).globalNotifyStateChanged(evergreenService, currentState);


    }
}