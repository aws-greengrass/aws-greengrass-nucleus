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
        Mockito.verify(stateTopic).setValue(Long.MAX_VALUE, State.NEW);
        Mockito.verify(stateTopic).validate(validatorArgumentCaptor.capture());
//        Mockito.verify(stateTopic).subscribe(evergreenService);
        Mockito.verifyNoMoreInteractions(stateTopic);

        // verify validator
        Validator validator = validatorArgumentCaptor.getValue();
        State returnedState = (State) validator.validate(State.NEW, null);
        Assertions.assertSame(State.NEW, returnedState);

        Assertions.assertSame(State.NEW, evergreenService.getActiveState());

    }

    @Test
    void GIVEN_a_service_in_new_state_WHEN_broadcastStateChange_to_installed_THEN_installed_state_gets_published() {
        // GIVEN
        State currentState = State.NEW;
        State newState = State.INSTALLED;

        Mockito.when(stateTopic.getOnce()).thenReturn(currentState);
        Mockito.when(context.getLog()).thenReturn(log);
        Mockito.when(config.getFullName()).thenReturn(EVERGREEN_SERVICE_FULL_NAME);

        // WHEN
        evergreenService.broadcastStateChange(newState);

        // THEN
        InOrder inOrder = Mockito.inOrder(stateTopic, context, log);
        inOrder.verify(stateTopic).getOnce();
        inOrder.verify(context).getLog();
        inOrder.verify(stateTopic).setValue(Long.MAX_VALUE, newState);
        inOrder.verify(context).globalNotifyStateChanged(evergreenService, currentState, newState);


    }
}