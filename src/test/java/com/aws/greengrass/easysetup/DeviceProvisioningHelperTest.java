/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.IamSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.CreateRoleAliasResponse;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.CreateThingResponse;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointResponse;
import software.amazon.awssdk.services.iot.model.DescribeRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.DescribeRoleAliasResponse;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyResponse;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ListAttachedPoliciesRequest;
import software.amazon.awssdk.services.iot.model.ListAttachedPoliciesResponse;
import software.amazon.awssdk.services.iot.model.Policy;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.RoleAliasDescription;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collections;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeviceProvisioningHelperTest {
    private static final String TEST_REGION = "us-east-1";

    @TempDir
    Path tempRootDir;
    @Mock
    private IotClient iotClient;
    @Mock
    private IamClient iamClient;
    @Mock
    private GreengrassV2Client greengrassClient;
    @Mock
    private GetPolicyResponse getPolicyResponse;
    @Mock
    private CreateThingResponse createThingResponse;
    @Mock
    private DescribeEndpointResponse describeEndpointResponse;
    @Mock
    private DescribeRoleAliasResponse describeRoleAliasResponse;
    @Mock
    private CreateRoleAliasResponse createRoleAliasResponse;
    @Mock
    private CreateKeysAndCertificateResponse createKeysAndCertificateResponse;
    @Mock
    private GetRoleResponse getRoleResponse;
    @Mock
    private CreateRoleResponse createRoleResponse;
    @Mock
    private ListAttachedPoliciesResponse listAttachedPoliciesResponse;
    private DeviceProvisioningHelper deviceProvisioningHelper;
    private Kernel kernel;

    private String getThingArn() {
        return Arn.builder().service("testService")
                .region(TEST_REGION).accountId("12345").partition("testPartition").resource("testResoruce")
                .build().toString();
    }

    @BeforeEach
    void setup() {
        deviceProvisioningHelper = new DeviceProvisioningHelper(System.out, iotClient, iamClient, greengrassClient);
    }

    @AfterEach
    void cleanup() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_test_create_thing_WHEN_thing_policy_exists_THEN_use_existing_thing_policy() {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenReturn(getPolicyResponse);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing");
        verify(iotClient, times(0)).createPolicy(any(CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_test_create_thing_WHEN_thing_policy_doesnt_exist_THEN_create_thing_policy() {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenThrow(ResourceNotFoundException.class);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing");
        verify(iotClient, times(1)).createPolicy(any(CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_test_setup_tes_role_WHEN_role_alias_exists_THEN_use_existing_role_alias() {
        when(iotClient.describeRoleAlias(any(DescribeRoleAliasRequest.class))).thenReturn(describeRoleAliasResponse);
        when(describeRoleAliasResponse.roleAliasDescription()).thenReturn(RoleAliasDescription.builder().build());
        deviceProvisioningHelper.setupIoTRoleForTes("TestRoleName", "TestRoleAliasName", "TestCertArn");
        verify(iotClient, times(0)).createRoleAlias(any(CreateRoleAliasRequest.class));
        verify(iamClient, times(0)).getRole(any(GetRoleRequest.class));
        verify(iamClient, times(0)).createRole(any(CreateRoleRequest.class));
    }

    @Test
    void GIVEN_test_setup_tes_role_WHEN_role_alias_doesnt_exist_and_role_exists_THEN_use_existing_role() {
        when(iotClient.describeRoleAlias(any(DescribeRoleAliasRequest.class)))
                .thenThrow(ResourceNotFoundException.class);
        when(iamClient.getRole(any(GetRoleRequest.class))).thenReturn(getRoleResponse);
        when(getRoleResponse.role()).thenReturn(Role.builder().build());
        when(iotClient.createRoleAlias(any(CreateRoleAliasRequest.class))).thenReturn(createRoleAliasResponse);
        deviceProvisioningHelper.setupIoTRoleForTes("TestRoleName", "TestRoleAliasName", "TestCertArn");
        verify(iotClient, times(1)).createRoleAlias(any(CreateRoleAliasRequest.class));
        verify(iamClient, times(1)).getRole(any(GetRoleRequest.class));
        verify(iamClient, times(0)).createRole(any(CreateRoleRequest.class));
    }

    @Test
    void GIVEN_test_setup_tes_role_WHEN_role_alias_doesnt_exist_and_role_doesnt_exist_THEN_create_role_and_alias() {
        when(iotClient.describeRoleAlias(any(DescribeRoleAliasRequest.class)))
                .thenThrow(ResourceNotFoundException.class);
        when(iamClient.getRole(any(GetRoleRequest.class))).thenThrow(ResourceNotFoundException.class);
        when(iamClient.createRole(any(CreateRoleRequest.class))).thenReturn(createRoleResponse);
        when(createRoleResponse.role()).thenReturn(Role.builder().build());
        when(iotClient.createRoleAlias(any(CreateRoleAliasRequest.class))).thenReturn(createRoleAliasResponse);
        deviceProvisioningHelper.setupIoTRoleForTes("TestRoleName", "TestRoleAliasName", "TestCertArn");
        verify(iotClient, times(1)).createRoleAlias(any(CreateRoleAliasRequest.class));
        verify(iamClient, times(1)).getRole(any(GetRoleRequest.class));
        verify(iamClient, times(1)).createRole(any(CreateRoleRequest.class));
    }

    @Test
    void GIVEN_test_update_device_config_WHEN_thing_info_provided_THEN_add_config_to_config_store()
            throws Exception {
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "dataEndpoint",
                        "credEndpoint"), TEST_REGION, "roleAliasName");
        assertEquals("thingname", kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME).getOnce());
        assertEquals("roleAliasName", kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                        IOT_ROLE_ALIAS_TOPIC).getOnce());
    }

    @Test
    void GIVEN_test_clean_thing_WHEN_thing_info_and_cert_and_things_deleted() {
        when(iotClient.listAttachedPolicies(any(ListAttachedPoliciesRequest.class)))
                .thenReturn(listAttachedPoliciesResponse);
        when(listAttachedPoliciesResponse.policies()).thenReturn(
                Collections.singletonList(Policy.builder().policyName("policyName").policyArn("policyArn").build()));

        deviceProvisioningHelper.cleanThing(iotClient,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "dataEndpoint",
                        "credEndpoint"), true);
        verify(iotClient, times(1)).detachThingPrincipal(any(DetachThingPrincipalRequest.class));
        verify(iotClient, times(1)).updateCertificate(any(UpdateCertificateRequest.class));
        verify(iotClient, times(1)).deleteCertificate(any(DeleteCertificateRequest.class));
        verify(iotClient, times(1)).deleteThing(any(DeleteThingRequest.class));
        verify(iotClient, times(1)).listAttachedPolicies(any(ListAttachedPoliciesRequest.class));
        verify(iotClient, times(1)).detachPolicy(any(DetachPolicyRequest.class));
        verify(iotClient, times(1)).deletePolicy(any(DeletePolicyRequest.class));
    }

    @Test
    void GIVEN_iam_client_factory_WHEN_test_get_iam_client_THEN_client_is_built_with_appropriate_configuration() {
        assertNotNull(IamSdkClientFactory.getIamClient(TEST_REGION));
    }

    @Test
    void GIVEN_iot_client_factory_WHEN_test_get_iot_client_THEN_client_is_built_with_appropriate_configuration() throws URISyntaxException {
        assertNotNull(IotSdkClientFactory.getIotClient(TEST_REGION, IotSdkClientFactory.EnvironmentStage.PROD));

        assertNotNull(IotSdkClientFactory.getIotClient(Region.US_EAST_1,
                StaticCredentialsProvider.create(AwsSessionCredentials.create("Test", "Test", "Test"))));

        assertNotNull(IotSdkClientFactory.getIotClient(TEST_REGION, IotSdkClientFactory.EnvironmentStage.PROD,
                Collections.singleton(Exception.class)));
    }
}
