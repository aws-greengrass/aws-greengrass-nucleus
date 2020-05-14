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

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.STATE_TOPIC_NAME;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class EGServiceTestUtil {

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
        Mockito.when(config.createLeafChild(eq(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC))).thenReturn(requiresTopic);
        Mockito.when(config.getName()).thenReturn(serviceFullName);
        Mockito.when(requiresTopic.dflt(Mockito.any())).thenReturn(requiresTopic);
        Mockito.when(requiresTopic.getOnce()).thenReturn(new ArrayList<>());
        Mockito.when(config.getContext()).thenReturn(context);
        lenient().when(context.get(ExecutorService.class)).thenReturn(mock(ExecutorService.class));

        return config;
    }
}
