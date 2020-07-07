package com.aws.iot.evergreen.integrationtests.tes;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.tes.TokenExchangeService.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(EGExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TESIntegrationTest {

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
        kernel.parseArgs("-i", TESIntegrationTest.class.getResource("tesExample.yaml").toString());
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(AWS_REGION, System.out);
        provision(kernel);
    }

    @AfterEach
    void tearDown() {
        kernel.shutdown();
        deviceProvisioningHelper.cleanThing(IotSdkClientFactory.getIotClient(AWS_REGION), thingInfo);
        deviceProvisioningHelper.cleanUpIotRoleForTest(TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
    }

    @Test
    void GIVEN_iot_role_alias_WHEN_tes_is_queried_THEN_valid_credentials_are_returned() throws Exception {
        kernel.launch();
        Thread.sleep(5000);
        String url = kernel.getConfig().find(SETENV_CONFIG_NAMESPACE, "AWS_CONTAINER_CREDENTIALS_FULL_URI").getOnce().toString();
        assertNotNull(url);
        String credentials = Exec.sh(String.format("curl -s -o /dev/stdout %s", url));
        assertThat(credentials, matchesPattern(
                "\\{\"AccessKeyId\":\".+\",\"SecretAccessKey\":\".+\",\"Expiration\":\".+\",\"Token\":\".+\"\\}"));
    }

    void provision(Kernel kernel) throws IOException {
        thingInfo = deviceProvisioningHelper.createThing(IotSdkClientFactory.getIotClient(AWS_REGION), POLICY_NAME, THING_NAME);
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, AWS_REGION);
        deviceProvisioningHelper.setupIoTRoleForTes(TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
        deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, TES_ROLE_ALIAS_NAME);
        Topics tesTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS);
        if (tesTopics != null) {
            System.err.println(tesTopics);
        }
        tesTopics.createLeafChild(IOT_ROLE_ALIAS_TOPIC).withValue(TES_ROLE_ALIAS_NAME);

    }
}
