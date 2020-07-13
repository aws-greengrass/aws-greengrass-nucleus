package com.aws.iot.evergreen.integrationtests.tes;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.tes.TokenExchangeService.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EGExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("E2E")
class TESTest {

    private static Kernel kernel;
    private ThingInfo thingInfo;
    private DeviceProvisioningHelper deviceProvisioningHelper;
    private final static String POLICY_NAME = "TES_INTEG_TEST_POLICY";
    private final static String THING_NAME = "TES_INTEG_THING";
    private final static String AWS_REGION = "us-east-1";
    private final static String TES_ROLE_NAME = "TES_INTEG_ROLE";
    private final static String TES_ROLE_ALIAS_NAME = "TES_INTEG_ROLE_ALIAS";

    @BeforeEach
    void setupKernel() throws IOException {
        kernel = new Kernel();
        kernel.parseArgs("-i", TESTest.class.getResource("tesExample.yaml").toString());
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(AWS_REGION, System.out);
        provision(kernel);
    }

    @AfterEach
    void tearDown() {
        kernel.shutdown();
        deviceProvisioningHelper.cleanThing(IotSdkClientFactory.getIotClient(AWS_REGION), thingInfo);
        IotJobsUtils.cleanUpIotRoleForTest(IotSdkClientFactory.getIotClient(AWS_REGION),
                IamSdkClientFactory.getIamClient(),
                TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_THEN_valid_credentials_are_returned() throws Exception {
        CountDownLatch tesRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TOKEN_EXCHANGE_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                tesRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(tesRunning.await(5, TimeUnit.SECONDS));
        Thread.sleep(5000);
        String urlString = kernel.getConfig().find(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME).getOnce().toString();
        assertNotNull(urlString);
        URL url = new URL(urlString);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        int status = con.getResponseCode();
        assertEquals(status, 200);
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

    void provision(Kernel kernel) throws IOException {
        thingInfo = deviceProvisioningHelper.createThing(IotSdkClientFactory.getIotClient(AWS_REGION), POLICY_NAME, THING_NAME);
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, AWS_REGION);
        deviceProvisioningHelper.setupIoTRoleForTes(TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
        deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, TES_ROLE_ALIAS_NAME);
        Topics tesTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS);
        tesTopics.createLeafChild(IOT_ROLE_ALIAS_TOPIC).withValue(TES_ROLE_ALIAS_NAME);
        deviceProvisioningHelper.setUpEmptyPackagesForFirstPartyServices();

    }
}
