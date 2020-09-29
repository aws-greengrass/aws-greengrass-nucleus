package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_STATUS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.LOCAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({MockitoExtension.class})
public class DeploymentStatusKeeperTest {

    @Mock
    private DeploymentService deploymentService;

    private static final Function<Map<String, Object>, Boolean> DUMMY_CONSUMER = (details) -> false;
    private static final String DUMMY_SERVICE_NAME = "dummyService";
    private DeploymentStatusKeeper deploymentStatusKeeper;
    private Topics processedDeployments;
    private Context context;

    @BeforeEach
    public void setup() {
        context = new Context();
        Configuration config = new Configuration(context);
        Mockito.when(deploymentService.getRuntimeConfig()).thenReturn(
                config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC));

        deploymentStatusKeeper = new DeploymentStatusKeeper();
        deploymentStatusKeeper.setDeploymentService(deploymentService);
        processedDeployments = deploymentStatusKeeper.getProcessedDeployments();
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
    }

    @Test
    public void WHEN_registering_deployment_status_consumer_multiple_times_THEN_registration_fails_after_first_attempt() {
        assertTrue(deploymentStatusKeeper.
                registerDeploymentStatusConsumer(IOT_JOBS, DUMMY_CONSUMER, DUMMY_SERVICE_NAME));
        assertTrue(deploymentStatusKeeper.
                registerDeploymentStatusConsumer(LOCAL, DUMMY_CONSUMER, DUMMY_SERVICE_NAME));
        assertFalse(deploymentStatusKeeper.
                registerDeploymentStatusConsumer(IOT_JOBS, DUMMY_CONSUMER, DUMMY_SERVICE_NAME));
        assertFalse(deploymentStatusKeeper.
                registerDeploymentStatusConsumer(LOCAL, DUMMY_CONSUMER, DUMMY_SERVICE_NAME));
    }

    @Test
    public void WHEN_deployment_status_update_received_THEN_consumers_called_with_update() {
        final Map<String, Object> updateOfTypeJobs = new HashMap<>();
        deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS, (details) -> {
            updateOfTypeJobs.putAll(details);
            return true;
        }, DUMMY_SERVICE_NAME);

        final Map<String, Object> updateOfTypeLocal = new HashMap<>();
        deploymentStatusKeeper.registerDeploymentStatusConsumer(LOCAL, (details) -> {
            updateOfTypeLocal.putAll(details);
            return true;
        }, DUMMY_SERVICE_NAME);

        deploymentStatusKeeper.persistAndPublishDeploymentStatus("iot_deployment", IOT_JOBS, JobStatus.SUCCEEDED.toString(), new HashMap<>());
        deploymentStatusKeeper.persistAndPublishDeploymentStatus("local_deployment", LOCAL, DeploymentStatus.SUCCEEDED.toString(),
                new HashMap<>());
        assertEquals(4, updateOfTypeJobs.size());
        assertEquals(3, updateOfTypeLocal.size());
        assertEquals(updateOfTypeJobs.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID), "iot_deployment");
        assertEquals(Coerce.toEnum(JobStatus.class, updateOfTypeJobs.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS)),
                JobStatus.SUCCEEDED);
        assertEquals(updateOfTypeJobs.get(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS), new HashMap<>());
        assertEquals(Coerce.toEnum(Deployment.DeploymentType.class,
                updateOfTypeJobs.get(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE)),
                IOT_JOBS);
        assertEquals("local_deployment", updateOfTypeLocal.get(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID));
        assertEquals(LOCAL,
                Coerce.toEnum(Deployment.DeploymentType.class,
                        updateOfTypeLocal.get(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE)));
        assertEquals(DeploymentStatus.SUCCEEDED,
                Coerce.toEnum(DeploymentStatus.class,
                        updateOfTypeLocal.get(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_STATUS)));
    }

    @Test
    public void GIVEN_deployment_status_update_WHEN_consumer_return_true_THEN_update_is_removed_from_config() {
        deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS, (details) -> true, DUMMY_SERVICE_NAME);
        deploymentStatusKeeper.persistAndPublishDeploymentStatus("iot_deployment", IOT_JOBS, JobStatus.SUCCEEDED.toString(), new HashMap<>());
        context.runOnPublishQueueAndWait(() -> {});
        assertEquals(0, processedDeployments.children.size());
    }

    @Test
    public void GIVEN_local_deployment_status_update_WHEN_consumer_return_true_THEN_update_is_removed_from_config() {
        deploymentStatusKeeper.registerDeploymentStatusConsumer(LOCAL, (details) -> true, DUMMY_SERVICE_NAME);
        deploymentStatusKeeper.persistAndPublishDeploymentStatus("local_deployment", LOCAL,
                DeploymentStatus.SUCCEEDED.toString(), new HashMap<>());
        context.runOnPublishQueueAndWait(() -> {});
        assertEquals(0, processedDeployments.children.size());
    }

    @Test
    public void GIVEN_deployment_status_update_WHEN_consumer_return_false_THEN_update_is_not_removed() {
        deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS, (details) -> false, DUMMY_SERVICE_NAME);
        deploymentStatusKeeper.persistAndPublishDeploymentStatus("iot_deployment", IOT_JOBS, JobStatus.SUCCEEDED.toString(), new HashMap<>());
        assertEquals(1, processedDeployments.children.size());
    }

    @Test
    public void GIVEN_consumer_returned_false_WHEN_consumer_successfully_consumes_update_THEN_update_is_removed_from_config() {
        AtomicInteger consumerInvokeCount = new AtomicInteger();
        //initially consumer is not able to process the status
        AtomicBoolean consumerReturnValue = new AtomicBoolean(false);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS, (details) -> {
            consumerInvokeCount.getAndIncrement();
            return consumerReturnValue.get();
        }, DUMMY_SERVICE_NAME);
        // DeploymentStatusKeeper will retain update as consumer returns false
        deploymentStatusKeeper.persistAndPublishDeploymentStatus("iot_deployment", IOT_JOBS, JobStatus.SUCCEEDED.toString(), new HashMap<>());
        assertEquals(1, consumerInvokeCount.get());

        // updating the consumer return value to true
        consumerReturnValue.set(true);
        // DeploymentStatusKeeper invokes consumers with stored updates
        deploymentStatusKeeper.publishPersistedStatusUpdates(IOT_JOBS);
        // assert consumer is invoked second time
        assertEquals(2, consumerInvokeCount.get());
        // assert update is removed as consumer returns true
        context.runOnPublishQueueAndWait(() -> {});
        assertEquals(0, processedDeployments.children.size());
        // nothing happens as there is no persisted updates
        deploymentStatusKeeper.publishPersistedStatusUpdates(IOT_JOBS);
        assertEquals(2, consumerInvokeCount.get());

    }
}



