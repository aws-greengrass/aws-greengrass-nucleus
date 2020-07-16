package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class TokenExchangeServiceTest extends EGServiceTestUtil {
    @Mock
    IotConnectionManager mockIotConnectionManager;


    ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);

    @BeforeEach
    public void setup() {
        // initialize Evergreen service specific mocks
        serviceFullName = "TokenExchangeService";
        initializeMockedConfig();
        when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3000})
    public void GIVEN_token_exchange_service_WHEN_started_THEN_correct_env_set(int port) throws Exception {
        Topic mockTopic = mock(Topic.class);
        when(mockTopic.dflt(anyInt())).thenReturn(mockTopic);
        when(mockTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, mockTopic);
            return null;
        });
        when(mockTopic.getOnce()).thenReturn(port);

        Topic mockUriTopic = mock(Topic.class);
        Topic mockAuthTopic = mock(Topic.class);
        Topics mockConfig = mock(Topics.class);
        when(config.getRoot()).thenReturn(mockConfig);
        when(config.lookup(any())).thenReturn(mockTopic);
        when(mockConfig.lookup(EvergreenService.SETENV_CONFIG_NAMESPACE, TokenExchangeService.TES_URI_ENV_VARIABLE_NAME)).thenReturn(mockUriTopic);
        when(mockConfig.lookup(EvergreenService.SETENV_CONFIG_NAMESPACE, TokenExchangeService.TES_AUTH_ENV_VARIABLE_NAME)).thenReturn(mockAuthTopic);


        TokenExchangeService tes = new TokenExchangeService(config, mockIotConnectionManager);
        tes.startup();
        Thread.sleep(5000L);
        tes.shutdown();

        verify(mockAuthTopic).withValue("Basic auth_not_supported");
        verify(mockUriTopic).withValue(stringArgumentCaptor.capture());
        String tesUrl = stringArgumentCaptor.getValue();
        URI uri = new URI(tesUrl);
        assertEquals("localhost", uri.getHost());
        assertEquals("/2016-11-01/credentialprovider/", uri.getPath());
        if (port == 0) {
            // If port is 0, then service should url should be set to random port
            assertTrue(uri.getPort() > 0);
        } else {
            assertEquals(port, uri.getPort());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"  "})
    @NullAndEmptySource
    public void GIVEN_token_exchange_service_WHEN_started_with_empty_role_alias_THEN_server_errors_out(String roleAlias) throws InterruptedException {
        //Set mock for role topic
        Topic roleTopic = mock(Topic.class);
        when(roleTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, roleTopic);
            return null;
        });
        when(roleTopic.getOnce()).thenReturn(roleAlias);

        // set mock for port topic
        Topic portTopic = mock(Topic.class);
        when(portTopic.dflt(anyInt())).thenReturn(portTopic);
        when(portTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, portTopic);
            return null;
        });
        when(portTopic.getOnce()).thenReturn(8080);

        when(config.lookup(PARAMETERS_CONFIG_KEY, TokenExchangeService.IOT_ROLE_ALIAS_TOPIC)).thenReturn(roleTopic);
        when(config.lookup(PARAMETERS_CONFIG_KEY, TokenExchangeService.PORT_TOPIC)).thenReturn(portTopic);

        TokenExchangeService tes = spy(new TokenExchangeService(config, mockIotConnectionManager));
        ArgumentCaptor<State> stateArgumentCaptor = ArgumentCaptor.forClass(State.class);
        doNothing().when(tes).reportState(stateArgumentCaptor.capture());
        tes.startup();
        assertEquals(State.ERRORED, stateArgumentCaptor.getValue());
    }
}
