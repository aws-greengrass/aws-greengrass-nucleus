package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.UpdateSystemSafelyService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import static com.aws.iot.evergreen.kernel.EvergreenService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.STATE_TOPIC_NAME;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class EGServiceTestUtil {

    protected String serviceFullName = "EvergreenServiceFullName";

    @Mock
    protected UpdateSystemSafelyService mockSafeUpdateService;

    @Mock
    protected Topics config;

    @Mock
    protected Topic stateTopic;

    @Mock
    protected Topic requiresTopic;

    @Mock
    protected Topics runtimeStoreTopic;

    @Mock
    protected Topics privateStoreTopic;

    @Mock
    protected Context context;

    public Topics initializeMockedConfig() {
        lenient().when(config.lookupTopics(eq(RUNTIME_STORE_NAMESPACE_TOPIC), anyString(), anyString()))
                .thenReturn(runtimeStoreTopic);
        lenient().when(config.lookupTopics(eq(PRIVATE_STORE_NAMESPACE_TOPIC), anyString(), anyString()))
                .thenReturn(privateStoreTopic);
        lenient().when(config.lookupTopics(eq(RUNTIME_STORE_NAMESPACE_TOPIC))).thenReturn(runtimeStoreTopic);
        lenient().when(config.lookupTopics(eq(PRIVATE_STORE_NAMESPACE_TOPIC))).thenReturn(privateStoreTopic);
        lenient().when(privateStoreTopic.createLeafChild(eq(STATE_TOPIC_NAME))).thenReturn(stateTopic);
        when(config.createLeafChild(eq(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC))).thenReturn(requiresTopic);
        when(config.getName()).thenReturn(serviceFullName);
        when(requiresTopic.dflt(Mockito.any())).thenReturn(requiresTopic);
        when(requiresTopic.getOnce()).thenReturn(new ArrayList<>());
        when(config.getContext()).thenReturn(context);
        lenient().when(context.get(ExecutorService.class)).thenReturn(mock(ExecutorService.class));
        lenient().when(context.get(Kernel.class)).thenReturn(mock(Kernel.class));
        lenient().when(context.get(eq(UpdateSystemSafelyService.class))).thenReturn(mockSafeUpdateService);

        return config;
    }
}
