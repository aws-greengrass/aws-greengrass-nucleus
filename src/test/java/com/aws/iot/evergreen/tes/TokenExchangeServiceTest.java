package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class TokenExchangeServiceTest extends EGServiceTestUtil {
    @Mock
    IotConnectionManager mockIotConnectionManager;

    @BeforeEach
    public void setup() {
        // initialize Evergreen service specific mocks
        serviceFullName = "TokenExchangeService";
        initializeMockedConfig();
        when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
    }

    @Test
    public void GIVEN_Token_Exchange_Service_Test_WHEN_Started_THEN_Server_Starts() throws InterruptedException {
        //TOOD: add more tests
        Topic mockTopic = mock(Topic.class);
        when(mockTopic.dflt(anyInt())).thenReturn(mockTopic);
        when(mockTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, mockTopic);
            return null;
        });
        when(mockTopic.getOnce()).thenReturn(0);
        Topics mockConfig = mock(Topics.class);
        when(config.getRoot()).thenReturn(mockConfig);
        when(config.lookup(any())).thenReturn(mockTopic);
        when(mockConfig.lookup(anyString(), anyString())).thenReturn(mockTopic);

        TokenExchangeService tes = new TokenExchangeService(config, mockIotConnectionManager);
        tes.startup();
        Thread.sleep(5000L);
        tes.shutdown();
        verify(mockTopic).withValue("http://localhost:0" + HttpServerImpl.URL);
        verify(mockTopic).withValue("Basic auth_not_supported");
    }
}
