/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class ProvisioningConfigUpdateHelperTest {
    private static final String MOCK_CERTIFICATE_PATH = "MOCK_CERTIFICATE_PATH";
    private static final String MOCK_PRIVATE_KEY_PATH = "MOCK_PRIVATE_KEY_PATH";
    private static final String MOCK_THING_NAME = "MOCK_THING_NAME";
    private static final String MOCK_ROOT_CA_PATH = "MOCK_ROOT_CA_PATH";
    private static final String MOCK_AWS_REGION = "MOCK_AWS_REGION";
    private static final String MOCK_CREDENTIALS_ENDPOINT = "MOCK_CREDENTIALS_ENDPOINT";
    private static final String MOCK_IOT_DATA_ENDPOINT = "MOCK_IOT_DATA_ENDPOINT";
    private static final String MOCK_IOT_ROLE_ALIAS = "MOCK_IOT_ROLE_ALIAS";

    private ProvisioningConfigUpdateHelper provisioningConfigUpdateHelper;
    @Mock
    private Kernel mockKernel;
    @Mock
    private Configuration mockConfig;
    @Mock
    private Context mockContext;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Mock
    private Topics mockSystemConfiguration;
    @Mock
    private Topics mockNucleusConfiguration;


    @BeforeEach
    public void setup() {
        provisioningConfigUpdateHelper = new ProvisioningConfigUpdateHelper(mockKernel);
    }

    @Test
    public void testUpdateSystemConfiguration() {
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockConfig.lookupTopics(eq(SYSTEM_NAMESPACE_KEY))).thenReturn(mockSystemConfiguration);
        provisioningConfigUpdateHelper.updateSystemConfiguration(createSystemConfiguration(),
                UpdateBehaviorTree.UpdateBehavior.MERGE);
        ArgumentCaptor<UpdateBehaviorTree> behaviorCaptor = ArgumentCaptor.forClass(UpdateBehaviorTree.class);
        ArgumentCaptor<Map> updateMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockSystemConfiguration, times(1))
                .updateFromMap(updateMapCaptor.capture(), behaviorCaptor.capture());
        Map<String, Object> configMap = updateMapCaptor.getValue();
        assertEquals(MOCK_CERTIFICATE_PATH, configMap.get(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH));
        assertEquals(MOCK_PRIVATE_KEY_PATH, configMap.get(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH));
        assertEquals(MOCK_THING_NAME, configMap.get(DeviceConfiguration.DEVICE_PARAM_THING_NAME));
        assertEquals(MOCK_ROOT_CA_PATH, configMap.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH));
        assertEquals(UpdateBehaviorTree.UpdateBehavior.MERGE, behaviorCaptor.getValue().getBehavior());
    }

    @Test
    public void testUpdateSystemConfiguration_null_parameter() {
        assertThrows(NullPointerException.class, ()-> provisioningConfigUpdateHelper.updateSystemConfiguration(null,
                UpdateBehaviorTree.UpdateBehavior.MERGE));
        assertThrows(NullPointerException.class, ()-> provisioningConfigUpdateHelper.updateSystemConfiguration(createSystemConfiguration(),
                null));
        assertThrows(NullPointerException.class, ()-> provisioningConfigUpdateHelper.updateNucleusConfiguration(null,
                UpdateBehaviorTree.UpdateBehavior.MERGE));
        assertThrows(NullPointerException.class, ()-> provisioningConfigUpdateHelper.updateNucleusConfiguration(createNucleusConfiguration(),
                null));
    }

    @Test
    public void testUpdateSystemConfiguration_partial_update() {
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockConfig.lookupTopics(eq(SYSTEM_NAMESPACE_KEY))).thenReturn(mockSystemConfiguration);
        ProvisionConfiguration.SystemConfiguration systemConfiguration =
                new ProvisionConfiguration.SystemConfiguration();
        systemConfiguration.setThingName(MOCK_THING_NAME);
        systemConfiguration.setRootCAPath(MOCK_ROOT_CA_PATH);
        provisioningConfigUpdateHelper.updateSystemConfiguration(systemConfiguration,
                UpdateBehaviorTree.UpdateBehavior.MERGE);
        ArgumentCaptor<UpdateBehaviorTree> behaviorCaptor = ArgumentCaptor.forClass(UpdateBehaviorTree.class);
        ArgumentCaptor<Map> updateMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockSystemConfiguration, times(1))
                .updateFromMap(updateMapCaptor.capture(), behaviorCaptor.capture());
        Map<String, Object> configMap = updateMapCaptor.getValue();
        assertFalse(configMap.containsKey(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH));
        assertFalse(configMap.containsKey(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH));

        assertEquals(MOCK_THING_NAME, configMap.get(DeviceConfiguration.DEVICE_PARAM_THING_NAME));
        assertEquals(MOCK_ROOT_CA_PATH, configMap.get(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH));
        assertEquals(UpdateBehaviorTree.UpdateBehavior.MERGE, behaviorCaptor.getValue().getBehavior());
    }



    @Test
    public void testUpdateNucleusConfiguration() {
        mockNucleusConfiguration();
        provisioningConfigUpdateHelper.updateNucleusConfiguration(createNucleusConfiguration(),
                UpdateBehaviorTree.UpdateBehavior.MERGE);
        ArgumentCaptor<UpdateBehaviorTree> behaviorCaptor = ArgumentCaptor.forClass(UpdateBehaviorTree.class);
        ArgumentCaptor<Map> updateMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockNucleusConfiguration, times(1))
                .updateFromMap(updateMapCaptor.capture(), behaviorCaptor.capture());
        Map<String, Object> configMap = updateMapCaptor.getValue();
        assertEquals(MOCK_IOT_DATA_ENDPOINT, configMap.get(DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT));
        assertEquals(MOCK_CREDENTIALS_ENDPOINT, configMap.get(DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT));
        assertEquals(MOCK_IOT_ROLE_ALIAS, configMap.get(DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC));
        assertEquals(MOCK_AWS_REGION, configMap.get(DeviceConfiguration.DEVICE_PARAM_AWS_REGION));
        assertEquals(UpdateBehaviorTree.UpdateBehavior.MERGE, behaviorCaptor.getValue().getBehavior());
    }

    @Test
    public void testUpdateNucleusConfiguration_partialUpdate() {
        mockNucleusConfiguration();
        ProvisionConfiguration.NucleusConfiguration nucleusConfiguration =
                new ProvisionConfiguration.NucleusConfiguration();
        nucleusConfiguration.setAwsRegion(MOCK_AWS_REGION);
        nucleusConfiguration.setIotDataEndpoint(MOCK_IOT_DATA_ENDPOINT);
        provisioningConfigUpdateHelper.updateNucleusConfiguration(nucleusConfiguration,
                UpdateBehaviorTree.UpdateBehavior.MERGE);
        ArgumentCaptor<UpdateBehaviorTree> behaviorCaptor = ArgumentCaptor.forClass(UpdateBehaviorTree.class);
        ArgumentCaptor<Map> updateMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockNucleusConfiguration, times(1))
                .updateFromMap(updateMapCaptor.capture(), behaviorCaptor.capture());
        Map<String, Object> configMap = updateMapCaptor.getValue();

        assertFalse(configMap.containsKey(DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC));
        assertFalse(configMap.containsKey(DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT));

        assertEquals(MOCK_IOT_DATA_ENDPOINT, configMap.get(DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT));
        assertEquals(MOCK_AWS_REGION, configMap.get(DeviceConfiguration.DEVICE_PARAM_AWS_REGION));

        assertEquals(UpdateBehaviorTree.UpdateBehavior.MERGE, behaviorCaptor.getValue().getBehavior());
    }


    private void mockNucleusConfiguration() {
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockKernel.getContext()).thenReturn(mockContext);
        when(mockContext.get(eq(DeviceConfiguration.class))).thenReturn(mockDeviceConfiguration);
        when(mockDeviceConfiguration.getNucleusComponentName()).thenReturn(DEFAULT_NUCLEUS_COMPONENT_NAME);
        when(mockConfig.lookupTopics(eq(SERVICES_NAMESPACE_TOPIC), eq(DEFAULT_NUCLEUS_COMPONENT_NAME),
                eq(CONFIGURATION_CONFIG_KEY))).thenReturn(mockNucleusConfiguration);
    }
    private ProvisionConfiguration.SystemConfiguration createSystemConfiguration() {
        ProvisionConfiguration.SystemConfiguration systemConfiguration =
                new ProvisionConfiguration.SystemConfiguration();
        systemConfiguration.setCertificateFilePath(MOCK_CERTIFICATE_PATH);
        systemConfiguration.setPrivateKeyPath(MOCK_PRIVATE_KEY_PATH);
        systemConfiguration.setThingName(MOCK_THING_NAME);
        systemConfiguration.setRootCAPath(MOCK_ROOT_CA_PATH);
        return systemConfiguration;
    }

    private ProvisionConfiguration.NucleusConfiguration createNucleusConfiguration() {
        ProvisionConfiguration.NucleusConfiguration nucleusConfiguration =
                new ProvisionConfiguration.NucleusConfiguration();
        nucleusConfiguration.setAwsRegion(MOCK_AWS_REGION);
        nucleusConfiguration.setIotCredentialsEndpoint(MOCK_CREDENTIALS_ENDPOINT);
        nucleusConfiguration.setIotDataEndpoint(MOCK_IOT_DATA_ENDPOINT);
        nucleusConfiguration.setIotRoleAlias(MOCK_IOT_ROLE_ALIAS);
        return nucleusConfiguration;
    }


}
