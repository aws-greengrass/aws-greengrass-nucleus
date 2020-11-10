/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelVersion;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.COMPONENT_STORE_MAX_SIZE_BYTES;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS;
import static com.aws.greengrass.deployment.DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static com.aws.greengrass.lifecyclemanager.KernelCommandLine.MAIN_SERVICE_NAME;
import static com.aws.greengrass.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class TokenExchangeServiceTest extends GGServiceTestUtil {
    private static final String MOCK_ROLE_ALIAS = "ROLE_ALIAS";
    static ExecutorService executorService = Executors.newFixedThreadPool(1);
    @Mock
    AuthorizationHandler mockAuthZHandler;

    @Mock
    CredentialRequestHandler mockCredentialHandler;

    @Mock
    Kernel kernel;

    @Mock
    Configuration configuration;

    @Mock
    Context context;

    ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);

    ArgumentCaptor<Set<String>> operationsCaptor = ArgumentCaptor.forClass(Set.class);

    @BeforeEach
    void setup() {
        // initialize Greengrass service specific mocks
        serviceFullName = TOKEN_EXCHANGE_SERVICE_TOPICS;
        initializeMockedConfig();
        when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
        when(kernel.getConfig()).thenReturn(configuration);
        Topics servicesTopics = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
        Topic componentTypeTopic = Topic.of(context, SERVICE_TYPE_TOPIC_KEY, ComponentType.NUCLEUS.name());
        Topic componentVersionTopic = Topic.of(context, VERSION_CONFIG_KEY, KernelVersion.KERNEL_VERSION);
        Topic componentStoreSizeLimitTopic = Topic.of(context, COMPONENT_STORE_MAX_SIZE_BYTES, 10_000_000_000L);
        Topic deploymentPollingFrequency = Topic.of(context, SERVICE_TYPE_TOPIC_KEY, 15L);
        Topic mainDependenciesTopic = Topic.of(context, SERVICE_DEPENDENCIES_NAMESPACE_TOPIC,
                DEFAULT_NUCLEUS_COMPONENT_NAME);
        Topics root = mock(Topics.class);
        when(root.findOrDefault(new ArrayList<>(), SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME,
                SERVICE_DEPENDENCIES_NAMESPACE_TOPIC)).thenReturn(new ArrayList<String>());
        when(configuration.getRoot()).thenReturn(root);
        when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesTopics);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                .thenReturn(componentTypeTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, VERSION_CONFIG_KEY))
                .thenReturn(componentVersionTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                COMPONENT_STORE_MAX_SIZE_BYTES)).thenReturn(componentStoreSizeLimitTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                DEPLOYMENT_POLLING_FREQUENCY_SECONDS)).thenReturn(deploymentPollingFrequency);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME, SERVICE_DEPENDENCIES_NAMESPACE_TOPIC))
                .thenReturn(mainDependenciesTopic);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (context != null) {
            context.close();
        }
    }

    @AfterAll
    static void tearDown() {
        executorService.shutdown();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3000})
    void GIVEN_token_exchange_service_WHEN_started_THEN_correct_env_set(int port) throws Exception {
        Topic portTopic = mock(Topic.class);
        when(portTopic.dflt(anyInt())).thenReturn(portTopic);
        when(portTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, portTopic);
            return null;
        });
        when(portTopic.getOnce()).thenReturn(port);

        Topic roleTopic = mock(Topic.class);
        when(roleTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, roleTopic);
            return null;
        });
        when(roleTopic.getOnce()).thenReturn(MOCK_ROLE_ALIAS);
        when(roleTopic.withValue(anyString())).thenReturn(roleTopic);
        when(roleTopic.dflt(anyString())).thenReturn(roleTopic);

        Topic mockUriTopic = mock(Topic.class);
        Topics mockConfig = mock(Topics.class);
        when(config.getRoot()).thenReturn(mockConfig);
        when(config.lookup(PARAMETERS_CONFIG_KEY, TokenExchangeService.PORT_TOPIC)).thenReturn(portTopic);
        when(mockConfig.lookup(GreengrassService.SETENV_CONFIG_NAMESPACE, TokenExchangeService.TES_URI_ENV_VARIABLE_NAME)).thenReturn(mockUriTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                IOT_ROLE_ALIAS_TOPIC)).thenReturn(roleTopic);

        TokenExchangeService tes = new TokenExchangeService(config,
                mockCredentialHandler,
                mockAuthZHandler,
                executorService, deviceConfigurationWithRoleAlias(MOCK_ROLE_ALIAS));
        tes.postInject();
        tes.startup();
        Thread.sleep(5000L);
        tes.shutdown();

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

        verify(mockCredentialHandler).setIotCredentialsPath(stringArgumentCaptor.capture());
        assertEquals(MOCK_ROLE_ALIAS, stringArgumentCaptor.getValue());

        verify(mockAuthZHandler).registerComponent(stringArgumentCaptor.capture(), operationsCaptor.capture());
        assertEquals(TOKEN_EXCHANGE_SERVICE_TOPICS, stringArgumentCaptor.getValue());
        assertTrue(operationsCaptor.getValue().contains(TokenExchangeService.AUTHZ_TES_OPERATION));

    }

    @ParameterizedTest
    @ValueSource(strings = {"  "})
    @NullAndEmptySource
    void GIVEN_token_exchange_service_WHEN_started_with_empty_role_alias_THEN_server_errors_out(String roleAlias,
                                                                                                       ExtensionContext context) {
        ignoreExceptionUltimateCauseOfType(context, IllegalArgumentException.class);
        //Set mock for role topic
        Topic roleTopic = mock(Topic.class);
        when(roleTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, roleTopic);
            return null;
        });
        when(roleTopic.getOnce()).thenReturn(roleAlias);
        lenient().when(roleTopic.withValue(anyString())).thenReturn(roleTopic);
        when(roleTopic.dflt(anyString())).thenReturn(roleTopic);

        // set mock for port topic
        Topic portTopic = mock(Topic.class);
        when(portTopic.dflt(anyInt())).thenReturn(portTopic);
        when(portTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, portTopic);
            return null;
        });
        when(portTopic.getOnce()).thenReturn(8080);


        when(config.lookup(PARAMETERS_CONFIG_KEY, TokenExchangeService.PORT_TOPIC)).thenReturn(portTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                IOT_ROLE_ALIAS_TOPIC)).thenReturn(roleTopic);

        TokenExchangeService tes = spy(new TokenExchangeService(config,
                mockCredentialHandler,
                mockAuthZHandler,
                executorService, deviceConfigurationWithRoleAlias(roleAlias)));
        ArgumentCaptor<State> stateArgumentCaptor = ArgumentCaptor.forClass(State.class);
        doNothing().when(tes).reportState(stateArgumentCaptor.capture());
        tes.startup();
        assertEquals(State.ERRORED, stateArgumentCaptor.getValue());
    }

    @Test
    void GIVEN_token_exchange_service_WHEN_auth_errors_THEN_server_errors_out(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, AuthorizationException.class);
        doThrow(AuthorizationException.class).when(mockAuthZHandler).registerComponent(any(), any());
        //Set mock for role topic
        Topic roleTopic = mock(Topic.class);
        when(roleTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, roleTopic);
            return null;
        });
        when(roleTopic.getOnce()).thenReturn("TEST");
        when(roleTopic.withValue(anyString())).thenReturn(roleTopic);
        when(roleTopic.dflt(anyString())).thenReturn(roleTopic);

        // set mock for port topic
        Topic portTopic = mock(Topic.class);
        when(portTopic.dflt(anyInt())).thenReturn(portTopic);
        when(portTopic.subscribe(any())).thenAnswer((a) -> {
            ((Subscriber) a.getArgument(0)).published(WhatHappened.initialized, portTopic);
            return null;
        });
        when(portTopic.getOnce()).thenReturn(8080);
        when(config.lookup(PARAMETERS_CONFIG_KEY, TokenExchangeService.PORT_TOPIC)).thenReturn(portTopic);
        when(configuration.lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                IOT_ROLE_ALIAS_TOPIC)).thenReturn(roleTopic);

        TokenExchangeService tes = spy(new TokenExchangeService(config,
                mockCredentialHandler,
                mockAuthZHandler,
                executorService, deviceConfigurationWithRoleAlias("TEST")));
        ArgumentCaptor<State> stateArgumentCaptor = ArgumentCaptor.forClass(State.class);
        doNothing().when(tes).reportState(stateArgumentCaptor.capture());
        tes.postInject();
        assertEquals(State.ERRORED, stateArgumentCaptor.getValue());

        // this time make loadAuthorizationPolicy throw
        doNothing().when(mockAuthZHandler).registerComponent(any(), any());
        // GG_NEEDS_REVIEW: TODO: this no longer throws an exception; we need to parse the log to check the behavior
        //doThrow(AuthorizationException.class).when(mockAuthZHandler).loadAuthorizationPolicies(any(), any(), false);
        tes.postInject();
        assertEquals(State.ERRORED, stateArgumentCaptor.getValue());
    }

    private DeviceConfiguration deviceConfigurationWithRoleAlias(String roleAliasName) {
        DeviceConfiguration deviceConfiguration =  new DeviceConfiguration(kernel);
        deviceConfiguration.getIotRoleAlias().withValue(roleAliasName);
        return deviceConfiguration;
    }
}
