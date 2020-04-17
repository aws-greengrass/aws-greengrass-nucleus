package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
public class EGServiceTestUtil extends ExceptionLogProtector {

    public static final String STATE_TOPIC_NAME = "_State";
    protected String serviceFullName = "EvergreenServiceFullName";

    @Mock
    protected Topics config;

    @Mock
    protected Topic stateTopic;

    @Mock
    protected Topic requiresTopic;

    @Mock
    protected Context context;

    public Topics initializeMockedConfig() {
        Mockito.when(config.createLeafChild(eq(STATE_TOPIC_NAME))).thenReturn(stateTopic);
        Mockito.when(config.createLeafChild(eq("dependencies"))).thenReturn(requiresTopic);
        Mockito.when(config.getName()).thenReturn(serviceFullName);
        Mockito.when(requiresTopic.dflt(Mockito.any())).thenReturn(requiresTopic);
        Mockito.when(requiresTopic.getOnce()).thenReturn(new ArrayList<>());
        Mockito.when(config.getContext()).thenReturn(context);
        Mockito.when(context.get(ExecutorService.class)).thenReturn(mock(ExecutorService.class));

        return config;
    }
}
