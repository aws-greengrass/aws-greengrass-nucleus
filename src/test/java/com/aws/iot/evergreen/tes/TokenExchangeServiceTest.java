package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeviceConfigurationHelper;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class TokenExchangeServiceTest extends EGServiceTestUtil {
    @Mock
    DeviceConfigurationHelper mockDeviceHelper;

    @Mock
    TokenExchangeService.IotConnectionManagerFactory connectionManagerFactory;

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
        Topics mockConfig = mock(Topics.class);
        Topic mockTopic = mock(Topic.class);
        when(config.getRoot()).thenReturn(mockConfig);
        when(mockConfig.lookup(anyString(), anyString())).thenReturn(mockTopic);

        TokenExchangeService tes = new TokenExchangeService(config, mockDeviceHelper, connectionManagerFactory);
        tes.startup();
        Thread.sleep(5000L);
        tes.shutdown();
        verify(mockTopic).withValue("http://localhost:0" + HttpServerImpl.URL);
        verify(mockTopic).withValue("Basic auth_not_supported");
    }
}
