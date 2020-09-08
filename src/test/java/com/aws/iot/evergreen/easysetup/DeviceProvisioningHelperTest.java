package com.aws.iot.evergreen.easysetup;

import com.amazonaws.arn.Arn;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
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

import java.nio.file.Path;
import java.util.Collections;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.tes.TokenExchangeService.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class DeviceProvisioningHelperTest {
    private static final String TEST_REGION = "us-east-1";

    @TempDir
    Path tempRootDir;
    @Mock
    private IotClient iotClient;
    @Mock
    private IamClient iamClient;
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
        return Arn.builder().withService("testService")
                .withRegion(TEST_REGION).withAccountId("12345").withPartition("testPartition").withResource("testResoruce")
                .build().toString();
    }

    @BeforeEach
    public void setup() {
        deviceProvisioningHelper = new DeviceProvisioningHelper(System.out, iotClient, iamClient);
    }

    @AfterEach
    public void cleanup() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    public void GIVEN_test_create_thing_WHEN_thing_policy_exists_THEN_use_existing_thing_policy() {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenReturn(getPolicyResponse);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing");
        verify(iotClient, times(0)).createPolicy(any(CreatePolicyRequest.class));
    }

    @Test
    public void GIVEN_test_create_thing_WHEN_thing_policy_doesnt_exist_THEN_create_thing_policy() {
        when(iotClient.getPolicy(any(GetPolicyRequest.class))).thenThrow(ResourceNotFoundException.class);
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(createKeysAndCertificateResponse);
        when(iotClient.createThing(any(CreateThingRequest.class))).thenReturn(createThingResponse);
        when(iotClient.describeEndpoint(any(DescribeEndpointRequest.class))).thenReturn(describeEndpointResponse);
        deviceProvisioningHelper.createThing(iotClient, "TestThingPolicy", "TestThing");
        verify(iotClient, times(1)).createPolicy(any(CreatePolicyRequest.class));
    }

    @Test
    public void GIVEN_test_setup_tes_role_WHEN_role_alias_exists_THEN_use_existing_role_alias() {
        when(iotClient.describeRoleAlias(any(DescribeRoleAliasRequest.class))).thenReturn(describeRoleAliasResponse);
        when(describeRoleAliasResponse.roleAliasDescription()).thenReturn(RoleAliasDescription.builder().build());
        deviceProvisioningHelper.setupIoTRoleForTes("TestRoleName", "TestRoleAliasName", "TestCertArn");
        verify(iotClient, times(0)).createRoleAlias(any(CreateRoleAliasRequest.class));
        verify(iamClient, times(0)).getRole(any(GetRoleRequest.class));
        verify(iamClient, times(0)).createRole(any(CreateRoleRequest.class));
    }

    @Test
    public void GIVEN_test_setup_tes_role_WHEN_role_alias_doesnt_exist_and_role_exists_THEN_use_existing_role() {
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
    public void GIVEN_test_setup_tes_role_WHEN_role_alias_doesnt_exist_and_role_doesnt_exist_THEN_create_role_and_alias() {
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
    public void GIVEN_test_update_device_config_WHEN_thing_info_provided_THEN_add_config_to_config_store()
            throws Exception {
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());

        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "dataEndpoint",
                        "credEndpoint"), TEST_REGION);
        assertEquals("thingname", kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME).getOnce());
    }

    @Test
    public void GIVEN_test_tes_role_config_WHEN_role_info_provided_THEN_add_config_to_config_store() {
        kernel = new Kernel()
                .parseArgs("-i", getClass().getResource("blank_config.yaml").toString(), "-r", tempRootDir.toString());

        deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, "roleAliasName");
        assertEquals("roleAliasName", kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS, PARAMETERS_CONFIG_KEY,
                        IOT_ROLE_ALIAS_TOPIC).getOnce());
    }

    @Test
    public void GIVEN_test_clean_thing_WHEN_thing_info_and_cert_and_things_deleted() {
        when(iotClient.listAttachedPolicies(any(ListAttachedPoliciesRequest.class)))
                .thenReturn(listAttachedPoliciesResponse);
        when(listAttachedPoliciesResponse.policies()).thenReturn(
                Collections.singletonList(Policy.builder().policyName("policyName").policyArn("policyArn").build()));

        deviceProvisioningHelper.cleanThing(iotClient,
                new DeviceProvisioningHelper.ThingInfo(getThingArn(), "thingname", "certarn", "certid", "certpem",
                        KeyPair.builder().privateKey("privateKey").publicKey("publicKey").build(), "dataEndpoint",
                        "credEndpoint"));
        verify(iotClient, times(1)).detachThingPrincipal(any(DetachThingPrincipalRequest.class));
        verify(iotClient, times(1)).updateCertificate(any(UpdateCertificateRequest.class));
        verify(iotClient, times(1)).deleteCertificate(any(DeleteCertificateRequest.class));
        verify(iotClient, times(1)).deleteThing(any(DeleteThingRequest.class));
        verify(iotClient, times(1)).listAttachedPolicies(any(ListAttachedPoliciesRequest.class));
        verify(iotClient, times(1)).detachPolicy(any(DetachPolicyRequest.class));
        verify(iotClient, times(1)).deletePolicy(any(DeletePolicyRequest.class));
    }

    @Test
    public void GIVEN_iam_client_factory_WHEN_test_get_iam_client_THEN_client_is_built_with_appropriate_configuration() {
        assertNotNull(IamSdkClientFactory.getIamClient());
    }

    @Test
    public void GIVEN_iot_client_factory_WHEN_test_get_iot_client_THEN_client_is_built_with_appropriate_configuration() {
        assertNotNull(IotSdkClientFactory.getIotClient(TEST_REGION));

        assertNotNull(IotSdkClientFactory.getIotClient(Region.US_EAST_1,
                StaticCredentialsProvider.create(AwsSessionCredentials.create("Test", "Test", "Test"))));

        assertNotNull(IotSdkClientFactory.getIotClient(TEST_REGION, Collections.singleton(Exception.class)));
    }
}
