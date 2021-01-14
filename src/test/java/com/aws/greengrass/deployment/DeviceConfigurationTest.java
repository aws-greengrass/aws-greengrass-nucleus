/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeviceConfigurationTest {
    @Mock
    Kernel mockKernel;

    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() {
        Configuration configuration = mock(Configuration.class);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        when(configuration.lookup(anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.getRoot()).thenReturn(rootConfigTopics);
        when(mockKernel.getConfig()).thenReturn(configuration);
        lenient().when(mockKernel.getNucleusPaths()).thenReturn(nucleusPaths);
        Topics topic = mock(Topics.class);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        deviceConfiguration = new DeviceConfiguration(mockKernel);
    }

    @Test
    void GIVEN_good_config_WHEN_validate_THEN_succeeds() {
        assertDoesNotThrow(() -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void GIVEN_bad_cred_endpoint_config_WHEN_validate_THEN_fails() {
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-2.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
        assertEquals("IoT credential endpoint region xxxxxx.credentials.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_bad_data_endpoint_config_WHEN_validate_THEN_fails() {
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-2.amazonaws.com"));
        assertEquals("IoT data endpoint region xxxxxx-ats.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }
}
