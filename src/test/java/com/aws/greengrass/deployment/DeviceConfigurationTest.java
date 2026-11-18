/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.KernelCommandLine;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.AWS_IOT_THING_NAME_ENV;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_DERIVED_RECIPE_VARIABLES;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.FALLBACK_DEFAULT_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeviceConfigurationTest {
    @Mock
    Configuration configuration;
    @Mock
    KernelCommandLine kernelCommandLine;
    @Mock
    Topic mockTopic;
    @Mock
    Topics mockTopics;

    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() {
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        lenient().when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mockTopic);
        lenient().when(configuration.getRoot()).thenReturn(rootConfigTopics);

        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(mockTopics.subscribe(any())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString(), anyString(), anyString())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        lenient().when(configuration.lookupTopics(anyString())).thenReturn(topics);
    }

    @Test
    void WHEN_isDeviceConfiguredToTalkToCloud_THEN_validate_called_when_cache_is_null() throws DeviceConfigurationException {
        deviceConfiguration = spy(new DeviceConfiguration(configuration, kernelCommandLine));
        doNothing().when(deviceConfiguration).validate(true);
        deviceConfiguration.isDeviceConfiguredToTalkToCloud();
        verify(deviceConfiguration, times(1)).validate(true);
        deviceConfiguration.isDeviceConfiguredToTalkToCloud();
        verify(deviceConfiguration, times(1)).validate(true);
    }

    @Test
    void GIVEN_good_config_WHEN_validate_THEN_succeeds() {
        deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);
        assertDoesNotThrow(() -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void GIVEN_good_custom_config_WHEN_validate_THEN_succeeds() {
        deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);
        assertDoesNotThrow(() -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.custom-cred-endpoint.com",
                "xxxxxx.custom-data-endpoint.com"));
    }

    @Test
    void GIVEN_bad_cred_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-2.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
        assertEquals("IoT credential endpoint region xxxxxx.credentials.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_bad_data_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-2.amazonaws.com"));
        assertEquals("IoT data endpoint region xxxxxx-ats.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_config_WHEN_set_bad_aws_region_THEN_fallback_to_default(@Mock Context mockContext) {
        Topic testingTopic = Topic.of(mockContext, "testing", null);
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(testingTopic);
        when(mockTopic.withValue(anyString())).thenReturn(mockTopic);
        when(configuration.lookup(eq(SETENV_CONFIG_NAMESPACE), anyString())).thenReturn(mockTopic);

        deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);
        deviceConfiguration.setAWSRegion("nowhere-south-42");
        assertEquals(FALLBACK_DEFAULT_REGION, testingTopic.getOnce());
    }

    @Test
    void GIVEN_existing_config_including_nucleus_version_WHEN_init_device_config_THEN_use_nucleus_version_from_config()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());
            Topic nucleusVersionConfig = nucleusConfig.lookup(VERSION_CONFIG_KEY).withValue("99.99.99");

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, VERSION_CONFIG_KEY))
                    .thenReturn(nucleusVersionConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);
            when(configuration.findTopics(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME))
                    .thenReturn(nucleusConfig);
            deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);

            // Confirm version config didn't get overwritten with default
            assertEquals("99.99.99", Coerce.toString(nucleusVersionConfig));
            assertEquals("99.99.99", deviceConfiguration.getNucleusVersion());
        }

    }

    @Test
    void GIVEN_existing_config_with_no_nucleus_version_WHEN_init_device_config_THEN_use_default_nucleus_version()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);

            deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }

    @Test
    void GIVEN_no_existing_config_WHEN_init_device_config_THEN_use_default_nucleus_config() throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);

            deviceConfiguration = new DeviceConfiguration(configuration, kernelCommandLine);

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }

    private DeviceConfiguration deviceConfigWithDerived(Context context, String thingName, String pattern) {
        Topics derived = Topics.of(context, DEVICE_PARAM_DERIVED_RECIPE_VARIABLES, null);
        if (pattern != null) {
            derived.lookup(DEVICE_PARAM_THING_NAME).withValue(pattern);
        }
        lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME,
                CONFIGURATION_CONFIG_KEY, DEVICE_PARAM_DERIVED_RECIPE_VARIABLES)).thenReturn(derived);
        lenient().when(configuration.lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME))
                .thenReturn(Topic.of(context, DEVICE_PARAM_THING_NAME, thingName));
        lenient().when(configuration.lookup(SETENV_CONFIG_NAMESPACE, AWS_IOT_THING_NAME_ENV))
                .thenReturn(Topic.of(context, AWS_IOT_THING_NAME_ENV, ""));
        return new DeviceConfiguration(configuration, kernelCommandLine);
    }

    @Test
    void GIVEN_pattern_with_named_groups_WHEN_getDerivedValues_THEN_groups_extracted_and_unmatched_group_is_empty()
            throws Exception {
        try (Context context = new Context()) {
            // "zone" is declared via an optional group that does not participate in this match
            deviceConfiguration = deviceConfigWithDerived(context, "gateway-1a2b3c",
                    "^(?<prefix>[a-z]+)-(?<id>[0-9a-f]+)(?:-(?<zone>[a-z0-9]+))?$");

            Map<String, String> values = deviceConfiguration.getDerivedRecipeVariableValues();

            assertEquals("gateway", values.get("prefix"));
            assertEquals("1a2b3c", values.get("id"));
            assertEquals("", values.get("zone"));          // declared but unmatched -> empty
            assertFalse(values.containsKey("unknown"));     // undeclared -> absent (placeholder left as-is)
        }
    }

    @Test
    void GIVEN_pattern_that_does_not_match_thing_name_WHEN_getDerivedValues_THEN_declared_groups_are_empty()
            throws Exception {
        try (Context context = new Context()) {
            deviceConfiguration = deviceConfigWithDerived(context, "router",
                    "^(?<prefix>[a-z]+)-(?<id>[0-9a-f]+)$");

            Map<String, String> values = deviceConfiguration.getDerivedRecipeVariableValues();

            assertEquals("", values.get("prefix"));
            assertEquals("", values.get("id"));
        }
    }

    @Test
    void GIVEN_invalid_regex_WHEN_getDerivedValues_THEN_entry_skipped(ExtensionContext extensionContext)
            throws Exception {
        ignoreExceptionOfType(extensionContext, PatternSyntaxException.class);
        try (Context context = new Context()) {
            deviceConfiguration = deviceConfigWithDerived(context, "gateway-1a2b3c",
                    "^(?<id>[0-9a-f]+"); // unbalanced group -> PatternSyntaxException

            assertEquals(0, deviceConfiguration.getDerivedRecipeVariableValues().size());
        }
    }

    @Test
    void GIVEN_no_derived_config_WHEN_getDerivedValues_THEN_empty_map() throws Exception {
        try (Context context = new Context()) {
            deviceConfiguration = deviceConfigWithDerived(context, "gateway-1a2b3c", null);

            assertEquals(0, deviceConfiguration.getDerivedRecipeVariableValues().size());
        }
    }
}
