package com.aws.iot.evergreen.testcommons.testutilities;

import static org.mockito.ArgumentMatchers.eq;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EGServiceTestUtil {

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
        Mockito.when(config.getContext()).thenReturn(context);

        return config;
    }
}
