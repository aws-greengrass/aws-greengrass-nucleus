package com.aws.iot.evergreen.integrationtests.tes;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;

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
import static com.aws.iot.evergreen.tes.TokenExchangeService.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TES_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("E2E")
class TESTest extends BaseITCase {
    private static final int HTTP_200 = 200;
    private static final int HTTP_403 = 403;
    private Kernel kernel;
    private ThingInfo thingInfo;
    private DeviceProvisioningHelper deviceProvisioningHelper;
    private String roleId;
    private String roleName;
    private String roleAliasName;
    private static final String AWS_REGION = "us-east-1";
    private static final String TES_ROLE_NAME = "e2etest-TES_INTEG_ROLE";
    private static final String TES_ROLE_ALIAS_NAME = "e2etest-TES_INTEG_ROLE_ALIAS";

    @BeforeEach
    void setupKernel() throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", TESTest.class.getResource("tesExample.yaml").toString());
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(AWS_REGION, System.out);
        roleId = UUID.randomUUID().toString();
        roleName = TES_ROLE_NAME + roleId;
        roleAliasName = TES_ROLE_ALIAS_NAME + roleId;
        provision(kernel);
        CountDownLatch tesRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TOKEN_EXCHANGE_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                tesRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(tesRunning.await(5, TimeUnit.SECONDS));
        // Let tes start the http server
        Thread.sleep(5000);
    }

    @AfterEach
    void tearDown() {
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

        assertEquals(con.getResponseCode(), HTTP_200);
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
    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_without_auth_header_THEN_403_returned() throws Exception {
        String urlString = kernel.getConfig().find(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME).getOnce().toString();
        assertNotNull(urlString);
        URL url = new URL(urlString);

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        // We are not setting auth header
        assertEquals(con.getResponseCode(), HTTP_403);
        con.disconnect();

        // Set Auth header to invalid value now
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty ("Authorization", "Invalid token");
        assertEquals(con.getResponseCode(), HTTP_403);
        con.disconnect();

    }

    private void provision(Kernel kernel) throws IOException {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, AWS_REGION);
        deviceProvisioningHelper.setupIoTRoleForTes(roleName, roleAliasName,
                thingInfo.getCertificateArn());
        deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, roleAliasName);
        Topics tesTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS);
        tesTopics.createLeafChild(IOT_ROLE_ALIAS_TOPIC).withValue(roleAliasName);
        deviceProvisioningHelper.setUpEmptyPackagesForFirstPartyServices();
    }
}
