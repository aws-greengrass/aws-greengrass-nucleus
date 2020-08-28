package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.exceptions.DynamicConfigurationValidationException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentSafetyPolicy;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStore;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.iot.evergreen.ipc.services.configstore.exceptions.ConfigStoreIPCException;
import com.aws.iot.evergreen.kernel.Kernel;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicComponentConfigurationValidationTest {
    private static final String DEFAULT_EXISTING_SERVICE_VERSION = "1.0.0";
    private static final long DEFAULT_DEPLOYMENT_TIMESTAMP = 100;

    private IPCClient client;
    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;

    @BeforeEach
    void before() throws Exception {
        kernel = new Kernel();
        deploymentConfigMerger = new DeploymentConfigMerger(kernel);

        // launch kernel
        kernel.launch();
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        // Start a new service
        AtomicBoolean mainRestarted = new AtomicBoolean(false);
        AtomicBoolean serviceStarted = new AtomicBoolean(false);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState.equals(State.STARTING)) {
                mainRestarted.set(true);
            }
            if (service.getName().equals("OldService") && newState.equals(State.RUNNING) && oldState
                    .equals(State.STARTING)) {
                serviceStarted.set(true);
            }
        });
        HashMap<Object, Object> newConfig = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("OldService", new HashMap<Object, Object>() {{
                    put(PARAMETERS_CONFIG_KEY, new HashMap<Object, Object>() {{
                        put("ConfigKey1", "ConfigValue1");
                    }});
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                    }});
                    put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                }});
            }});
        }};
        deploymentConfigMerger.mergeInNewConfig(testDeployment(), newConfig).get(60, TimeUnit.SECONDS);

        assertTrue(mainRestarted.get());
        assertTrue(serviceStarted.get());
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_validates_config_THEN_deployment_is_successful()
            throws Throwable {
        try {
            // Subscribe to config validation on behalf of the running service
            KernelIPCClientConfig config = getIPCConfigForService("OldService", kernel);
            client = new IPCClientImpl(config);
            ConfigStore c = new ConfigStoreImpl(client);

            CountDownLatch eventReceivedByClient = new CountDownLatch(1);
            c.subscribeToValidateConfiguration((configMap) -> {
                assertThat(configMap, IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
                eventReceivedByClient.countDown();
                try {
                    c.sendConfigurationValidityReport(ConfigurationValidityStatus.VALID, null);
                } catch (ConfigStoreIPCException e) {
                }
            });

            // Attempt changing the configuration for the running service
            HashMap<Object, Object> newConfig = new HashMap<Object, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                    put("OldService", new HashMap<Object, Object>() {{
                        put(PARAMETERS_CONFIG_KEY, new HashMap<Object, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
                }});
            }};
            DeploymentResult result =
                    deploymentConfigMerger.mergeInNewConfig(testDeployment(), newConfig).get(60, TimeUnit.SECONDS);
            assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
            assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
        } finally {
            client.disconnect();
        }
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_invalidates_config_THEN_deployment_fails()
            throws Throwable {
        try {
            // Subscribe to config validation on behalf of the running service
            KernelIPCClientConfig config = getIPCConfigForService("OldService", kernel);
            client = new IPCClientImpl(config);
            ConfigStore c = new ConfigStoreImpl(client);

            CountDownLatch eventReceivedByClient = new CountDownLatch(1);
            c.subscribeToValidateConfiguration((configMap) -> {
                assertThat(configMap, IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
                eventReceivedByClient.countDown();
                try {
                    c.sendConfigurationValidityReport(ConfigurationValidityStatus.INVALID, null);
                } catch (ConfigStoreIPCException e) {
                }
            });

            // Attempt changing the configuration for the running service
            HashMap<Object, Object> newConfig = new HashMap<Object, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                    put("OldService", new HashMap<Object, Object>() {{
                        put(PARAMETERS_CONFIG_KEY, new HashMap<Object, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
                }});
            }};
            DeploymentResult result =
                    deploymentConfigMerger.mergeInNewConfig(testDeployment(), newConfig).get(60, TimeUnit.SECONDS);
            assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
            assertTrue(result.getFailureCause() instanceof DynamicConfigurationValidationException);
            assertTrue(result.getFailureCause().getMessage() != null && result.getFailureCause().getMessage().contains("Components reported that their to-be-deployed configuration is invalid"));
            assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
        } finally {
            client.disconnect();
        }
    }

    private Deployment testDeployment() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .timestamp(DEFAULT_DEPLOYMENT_TIMESTAMP).failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .deploymentSafetyPolicy(DeploymentSafetyPolicy.CHECK_SAFETY).build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }
}
