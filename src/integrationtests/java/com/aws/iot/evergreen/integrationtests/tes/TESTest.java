package com.aws.iot.evergreen.integrationtests.tes;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.NetworkUtils;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.tes.TokenExchangeService;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TES_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("E2E-INTRUSIVE")
class TESTest extends BaseITCase {
    private static final int HTTP_200 = 200;
    private static final int HTTP_403 = 403;
    private static Kernel kernel;
    private static ThingInfo thingInfo;
    private static DeviceProvisioningHelper deviceProvisioningHelper;
    private static String roleId;
    private static String roleName;
    private static String roleAliasName;
    private static NetworkUtils networkUtils;
    private static final String AWS_REGION = "us-east-1";
    private static final String TES_ROLE_NAME = "e2etest-TES_INTEG_ROLE";
    private static final String TES_ROLE_ALIAS_NAME = "e2etest-TES_INTEG_ROLE_ALIAS";

    @BeforeAll
    static void setupKernel() throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", TESTest.class.getResource("tesExample.yaml").toString());
        deviceProvisioningHelper = new DeviceProvisioningHelper(AWS_REGION, System.out);
        roleId = UUID.randomUUID().toString();
        roleName = TES_ROLE_NAME + roleId;
        roleAliasName = TES_ROLE_ALIAS_NAME + roleId;
        networkUtils = NetworkUtils.getByPlatform();
        provision(kernel);
        CountDownLatch tesRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TOKEN_EXCHANGE_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                tesRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(tesRunning.await(5, TimeUnit.SECONDS));
        // Let IAM role get created, it takes some time before role becomes active
        Thread.sleep(5000);
    }

    @AfterAll
    static void tearDown() {
        try {
            kernel.shutdown();
        } finally {
            deviceProvisioningHelper.cleanThing(IotSdkClientFactory.getIotClient(AWS_REGION,
                    Collections.singleton(InvalidRequestException.class)), thingInfo);
            IotJobsUtils.cleanUpIotRoleForTest(IotSdkClientFactory.getIotClient(AWS_REGION), IamSdkClientFactory.getIamClient(),
                    roleName, roleAliasName, thingInfo.getCertificateArn());
        }
    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_THEN_valid_credentials_are_returned() throws Exception {
        String urlString = kernel.getConfig().find(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME).getOnce().toString();
        assertNotNull(urlString);
        URL url = new URL(urlString);
        // Get the first token from the token map
        String token = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC, AuthenticationHandler.AUTH_TOKEN_LOOKUP_KEY).iterator().next().getName();
        assertNotNull(token);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty ("Authorization", token);
        assertEquals(HTTP_200, con.getResponseCode());
        StringBuilder response = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            String responseLine = in.readLine();
            while (responseLine != null) {
                response.append(responseLine);
                responseLine = in.readLine();
            }
        }
        con.disconnect();
        assertThat(response.toString(), matchesPattern(
                "\\{\"AccessKeyId\":\".+\",\"SecretAccessKey\":\".+\",\"Expiration\":\".+\",\"Token\":\".+\"\\}"));

        // Should serve cached credentials when network disabled
        try {
            networkUtils.disconnectNetwork();
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty ("Authorization", token);
            assertEquals(HTTP_200, con.getResponseCode());
            StringBuilder newResponse = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String newResponseLine = in.readLine();
                while (newResponseLine != null) {
                    newResponse.append(newResponseLine);
                    newResponseLine = in.readLine();
                }
            }
            con.disconnect();
            assertEquals(response.toString(), newResponse.toString());
        } finally {
            networkUtils.recoverNetwork();
        }

    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_without_auth_header_THEN_403_returned() throws Exception {
        String urlString = kernel.getConfig().find(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME).getOnce().toString();
        assertNotNull(urlString);
        URL url = new URL(urlString);

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        // We are not setting auth header
        assertEquals(HTTP_403, con.getResponseCode());
        con.disconnect();

        // Set Auth header to invalid value now
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty ("Authorization", "Invalid token");
        assertEquals(HTTP_403, con.getResponseCode());
        con.disconnect();

    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_within_kernel_bypassing_http_server_THEN_valid_credentials_are_returned() {
        TokenExchangeService tes = kernel.getContext().get(TokenExchangeService.class);
        AwsCredentials credentials = tes.resolveCredentials();

        assertNotNull(credentials.accessKeyId());
        assertNotNull(credentials.secretAccessKey());
    }

    private static void provision(Kernel kernel) throws IOException, DeviceConfigurationException {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, AWS_REGION);
        deviceProvisioningHelper.setupIoTRoleForTes(roleName, roleAliasName,
                thingInfo.getCertificateArn());
        deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, roleAliasName);
        deviceProvisioningHelper.setUpEmptyPackagesForFirstPartyServices();
    }
}
