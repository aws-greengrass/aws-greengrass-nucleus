package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.integrationtests.e2e.model.DeploymentRequest;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(EGExtension.class)
@Tag("E2E")
public class DeploymentCloudServiceIntegTest {

    private static final String CLOUD_SERVICE_URL =
            "http://everg-everg-h5kz3uxz114v-1885244560.us-east-1.elb.amazonaws.com/deployments";

    //TODO: after cloud service support creating deployments for things in custom accounts, remove the assume role.
    private static final String assumeRoleArn = "arn:aws:iam::432173944312:role/EvergreenDeviceTestAssumeRole";
    private static final String HTTP_HEADER_ETAG = "x-amzn-iot-eg-etag";
    private static final List<String> createdIotJobIdList = new ArrayList<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClients.createDefault();
    private final Logger logger = LogManager.getLogger(this.getClass());
    private Kernel kernel;

    private static IotClient iotClient;

    @TempDir
    Path tempRootDir;

    @BeforeAll
    @SuppressWarnings("PMD.CloseResource")
    static void setIotClient() {
        StsClient stsClient = StsClient.builder().build();

        AssumeRoleRequest roleRequest =
                AssumeRoleRequest.builder().roleArn(assumeRoleArn).roleSessionName(UUID.randomUUID().toString())
                        .build();
        AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
        Credentials sessionCredentials = roleResponse.credentials();

        AwsSessionCredentials awsCreds = AwsSessionCredentials
                .create(sessionCredentials.accessKeyId(), sessionCredentials.secretAccessKey(),
                        sessionCredentials.sessionToken());

        iotClient = IotClient.builder().credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .region(Region.US_EAST_1).build();
    }

    @AfterAll
    static void resetIotClient() {
        iotClient.close();
    }

    @AfterEach
    void cleanUp() {
        if (kernel != null) {
            kernel.shutdown();
        }
        Utils.cleanAllCreatedThings(iotClient);
        Utils.cleanAllCreatedThingGroups(iotClient);
        createdIotJobIdList.forEach(jobId -> Utils.cleanJob(iotClient, jobId));
    }

    @Test
    void GIVEN_blank_kernel_WHEN_create_deployment_on_thing_group_THEN_new_services_deployed_and_job_is_successful()
            throws Exception {
        System.setProperty("root", tempRootDir.toString());
        kernel = new Kernel().parseArgs("-i", getClass().getResource("blank_config.yaml").toString());

        Path localStoreContentPath = Paths.get(getClass().getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());

        Utils.ThingInfo thingInfo = Utils.createThing(iotClient);
        Utils.updateKernelConfigWithIotConfiguration(kernel, thingInfo);

        kernel.launch();

        // Create thing group and deployment
        String thingGroupArn = Utils.createThingGroupAndAddThing(iotClient, thingInfo);
        DeploymentDocument document = DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp", "SomeService"))
                .deploymentPackageConfigurationList(
                        Arrays.asList(new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null),
                                new DeploymentPackageConfiguration("SomeService", "1.0.0", null, null, null))).build();

        String jobId1 = sendCreateDeploymentRequest(thingGroupArn, document);

        // wait until deployment complete
        Utils.waitForJobExecutionStatusToSatisfy(iotClient, jobId1, thingInfo.thingName, Duration.ofMinutes(3),
                s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertEquals(State.FINISHED, kernel.getMain().getState());
        assertEquals(State.FINISHED, kernel.locate("CustomerApp").getState());
        assertEquals(State.FINISHED, kernel.locate("SomeService").getState());
    }

    private String sendCreateDeploymentRequest(String targetThingGroupArn, DeploymentDocument document)
            throws IOException {
        // construct http request
        HttpPost request = new HttpPost(CLOUD_SERVICE_URL);
        request.setHeader(HTTP_HEADER_ETAG, UUID.randomUUID().toString());

        String body = mapper.writeValueAsString(
                new DeploymentRequest(targetThingGroupArn, "Thing deployment", document, null));
        logger.atInfo("create-deployment").kv("request-body", body).log();
        HttpEntity entity = new StringEntity(body, ContentType.APPLICATION_JSON);
        request.setEntity(entity);

        // Calling cloud service
        HttpResponse response = client.execute(request);

        JsonNode responseJson = mapper.readTree(response.getEntity().getContent());
        logger.atInfo("create-deployment").kv("response-status", response.getStatusLine())
                .kv("response-body", responseJson.toPrettyString()).log();

        String jobId = responseJson.get("deploymentJob").get("jobId").asText();
        createdIotJobIdList.add(jobId);
        return jobId;
    }
}
