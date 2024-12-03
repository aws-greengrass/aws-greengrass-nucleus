/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.IamSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.RootCAUtils;
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
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
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
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CouplingBetweenObjects")
@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeviceProvisioningHelperTest {
    private static final String TEST_REGION = "us-east-1";

    @TempDir
    Path tempRootDir;
    @Mock
    private IotClient iotClient;
    @Mock
    private IamClient iamClient;
    @Mock
    private StsClient stsClient;
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
                .region(TEST_REGION).accountId("12345").partition("testPartition").resource("testResource")
                .build().toString();
    }

    @BeforeEach
    void setup() {
        deviceProvisioningHelper = new DeviceProvisioningHelper(System.out, iotClient, iamClient, stsClient,
                greengrassClient);
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
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing", "", "");
        verify(iotClient, times(0)).createPolicy(any(CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_test_create_thing_WHEN_thing_policy_doesnt_exist_THEN_create_thing_policy() {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenThrow(ResourceNotFoundException.class);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing", "", "");
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
    void GIVEN_create_and_attach_role_policy_WHEN_get_managed_policy_not_found_THEN_get_user_policy() {
        String accountId = "1234567890";
        String tesRole = "TestRoleName";
        String userPolicyArn = String.format("arn:aws:iam::%s:policy/%sAccess", accountId, tesRole);
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyRequest userPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(userPolicyArn)
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyResponse userPolicyRes =
                software.amazon.awssdk.services.iam.model.GetPolicyResponse.builder()
                        .policy(software.amazon.awssdk.services.iam.model.Policy.builder()
                                .policyName(String.format(tesRole + "Access", accountId, tesRole))
                                .arn(userPolicyArn)
                                .build())
                        .build();
        GetCallerIdentityResponse callerIdentityResponse = GetCallerIdentityResponse.builder()
                .account(accountId)
                .build();

        when(stsClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(callerIdentityResponse);
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                NoSuchEntityException.builder().message("Policy not found").build());
        when(iamClient.getPolicy(userPolicyReq)).thenReturn(userPolicyRes);

        deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1);

        verify(stsClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(2))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, never())
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_create_and_attach_role_policy_WHEN_get_managed_and_user_policy_not_found_THEN_create_user_policy() {
        String accountId = "1234567890";
        String tesRole = "TestRoleName";
        String userPolicyArn = String.format("arn:aws:iam::%s:policy/%sAccess", accountId, tesRole);
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyRequest userPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(userPolicyArn)
                        .build();
        software.amazon.awssdk.services.iam.model.CreatePolicyResponse createPolicyResponse =
                software.amazon.awssdk.services.iam.model.CreatePolicyResponse.builder()
                        .policy(software.amazon.awssdk.services.iam.model.Policy.builder()
                                .policyName(tesRole + "Access")
                                .arn(userPolicyArn)
                                .build())
                        .build();
        GetCallerIdentityResponse callerIdentityResponse = GetCallerIdentityResponse.builder()
                .account(accountId)
                .build();

        when(stsClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(callerIdentityResponse);
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                NoSuchEntityException.builder().message("Policy not found").build());
        when(iamClient.getPolicy(userPolicyReq)).thenThrow(
                NoSuchEntityException.builder().message("Policy not found").build());
        when(iamClient.createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class)))
                .thenReturn(createPolicyResponse);

        deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1);

        verify(stsClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(2))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, times(1))
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_create_and_attach_role_policy_WHEN_get_managed_policy_unauthorized_THEN_get_user_policy() {
        String accountId = "1234567890";
        String tesRole = "TestRoleName";
        String userPolicyArn = String.format("arn:aws:iam::%s:policy/%sAccess", accountId, tesRole);
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyRequest userPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(userPolicyArn)
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyResponse userPolicyRes =
                software.amazon.awssdk.services.iam.model.GetPolicyResponse.builder()
                        .policy(software.amazon.awssdk.services.iam.model.Policy.builder()
                                .policyName(String.format(tesRole + "Access", accountId, tesRole))
                                .arn(userPolicyArn)
                                .build())
                        .build();
        GetCallerIdentityResponse callerIdentityResponse = GetCallerIdentityResponse.builder()
                .account(accountId)
                .build();

        when(stsClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(callerIdentityResponse);
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                IamException.builder().message("User x is not authorized to perform iam::GetPolicy").build());
        when(iamClient.getPolicy(userPolicyReq)).thenReturn(userPolicyRes);

        deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1);

        verify(stsClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(2))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, never())
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_create_and_attach_role_policy_WHEN_get_managed_and_user_policy_unauthorized_THEN_create_policy() {
        String accountId = "1234567890";
        String tesRole = "TestRoleName";
        String userPolicyArn = String.format("arn:aws:iam::%s:policy/%sAccess", accountId, tesRole);
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyRequest userPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(userPolicyArn)
                        .build();
        software.amazon.awssdk.services.iam.model.CreatePolicyResponse createPolicyResponse =
                software.amazon.awssdk.services.iam.model.CreatePolicyResponse.builder()
                        .policy(software.amazon.awssdk.services.iam.model.Policy.builder()
                                .policyName(tesRole + "Access")
                                .arn(userPolicyArn)
                                .build())
                        .build();
        GetCallerIdentityResponse callerIdentityResponse = GetCallerIdentityResponse.builder()
                .account(accountId)
                .build();

        when(stsClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(callerIdentityResponse);
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                IamException.builder().message("User x is not authorized to perform iam::GetPolicy").build());
        when(iamClient.getPolicy(userPolicyReq)).thenThrow(
                IamException.builder().message("User x is not authorized to perform iam::GetPolicy").build());
        when(iamClient.createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class)))
                .thenReturn(createPolicyResponse);

        deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1);

        verify(stsClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(2))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, times(1))
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_create_and_attach_role_policy_WHEN_iam_error_in_get_managed_policy_THEN_fail() {
        String tesRole = "TestRoleName";
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                IamException.builder().message("Unknown IAM error").build());

        assertThrows(IamException.class,
                () -> deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1));

        verify(stsClient, never()).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(1))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, never())
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_create_and_attach_role_policy_WHEN_iam_error_in_get_user_policy_THEN_fail() {
        String accountId = "1234567890";
        String tesRole = "TestRoleName";
        software.amazon.awssdk.services.iam.model.GetPolicyRequest managedPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::aws:policy/%sAccess", tesRole))
                        .build();
        software.amazon.awssdk.services.iam.model.GetPolicyRequest userPolicyReq =
                software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                        .policyArn(String.format("arn:aws:iam::%s:policy/%sAccess", accountId, tesRole))
                        .build();
        GetCallerIdentityResponse callerIdentityResponse = GetCallerIdentityResponse.builder()
                .account(accountId)
                .build();

        when(stsClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(callerIdentityResponse);
        when(iamClient.getPolicy(managedPolicyReq)).thenThrow(
                IamException.builder().message("User x is not authorized to perform iam::GetPolicy").build());
        when(iamClient.getPolicy(userPolicyReq)).thenThrow(
                IamException.builder().message("Unknown IAM error").build());

        assertThrows(IamException.class,
                () -> deviceProvisioningHelper.createAndAttachRolePolicy(tesRole, Region.US_EAST_1));

        verify(stsClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
        verify(iamClient, times(2))
                .getPolicy(any(software.amazon.awssdk.services.iam.model.GetPolicyRequest.class));
        verify(iamClient, never())
                .createPolicy(any(software.amazon.awssdk.services.iam.model.CreatePolicyRequest.class));
    }

    @Test
    void GIVEN_test_update_device_config_WHEN_thing_info_provided_THEN_add_config_to_config_store()
            throws Exception {
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                        "xxxxxx.credentials.iot.us-east-1.amazonaws.com"), TEST_REGION, "roleAliasName", null);
        assertEquals("thingname", kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME).getOnce());
        assertEquals("roleAliasName", kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                        IOT_ROLE_ALIAS_TOPIC).getOnce());
    }

    @Test
    void GIVEN_device_config_WHEN_download_multiple_CAs_THEN_combine_and_save_at_Root_CA_file_location()
            throws Exception {
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                        "xxxxxx.credentials.iot.us-east-1.amazonaws.com"), TEST_REGION, "roleAliasName", null);
        Path certPath = kernel.getNucleusPaths().rootPath();
        Path caFilePath = certPath.resolve("rootCA.pem");
        File caFile = caFilePath.toFile();

        RootCAUtils.downloadRootCAToFile(caFile, RootCAUtils.AMAZON_ROOT_CA_3_URL);

        String certificates = new String(Files.readAllBytes(caFile.toPath()), StandardCharsets.UTF_8);
        List<String> certificateArray = Arrays.stream(certificates.split(EncryptionUtils.CERTIFICATE_PEM_HEADER)).filter(s -> !s.isEmpty()).collect(Collectors.toList());

        assertEquals(2, certificateArray.size());
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

    @Test
    void GIVEN_endpoints_provided_WHEN_create_thing_THEN_deviceConfig_contains_provided_endpoint() throws Exception {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenReturn(getPolicyResponse);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        when(createKeysAndCertificateResponse.keyPair()).thenReturn(KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build());
        when(createKeysAndCertificateResponse.certificatePem()).thenReturn("certPem");
        when(describeEndpointResponse.endpointAddress()).thenReturn("c2ek3s7ppzzhur.credentials.iot.us-east-1.amazonaws.com");
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());
        DeviceProvisioningHelper.ThingInfo thingInfo = deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing", "mockEndpoint", "");

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, "us-east-1",
                "TestRoleAliasName", tempRootDir.resolve("TestCertPath").toString());
         assertEquals("mockEndpoint", kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC,
                DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY, DEVICE_PARAM_IOT_DATA_ENDPOINT).getOnce());
    }
}
