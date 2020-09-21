package com.aws.greengrass.integrationtests.deployment;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.exceptions.DynamicConfigurationValidationException;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.configstore.ConfigStore;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.greengrass.ipc.services.configstore.exceptions.ConfigStoreIPCException;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
public class DynamicComponentConfigurationValidationTest extends BaseITCase {
    private static final String DEFAULT_EXISTING_SERVICE_VERSION = "1.0.0";

    private IPCClient client;
    private ConfigStore configStore;
    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        kernel = new Kernel();
        deploymentConfigMerger = new DeploymentConfigMerger(kernel);
        kernel.parseArgs("-i",
                DynamicComponentConfigurationValidationTest.class.getResource("onlyMain.yaml").toString());

        // launch kernel
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(mainRunning.await(10, TimeUnit.SECONDS));

        // Start a new service
        AtomicBoolean mainRestarted = new AtomicBoolean(false);
        AtomicBoolean serviceStarted = new AtomicBoolean(false);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) || newState.equals(State.FINISHED)) {
                mainRestarted.set(true);
            }
            if (service.getName().equals("OldService") && newState.equals(State.RUNNING) && oldState
                    .equals(State.STARTING)) {
                serviceStarted.set(true);
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(GreengrassService::getName)
                .collect(Collectors.toList());
        serviceList.add("OldService");
        HashMap<String, Object> newConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                            kernel.getMain().getServiceConfig().lookupTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC)
                                    .toPOJO());
                }});
                put("OldService", new HashMap<String, Object>() {{
                    put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                        put("ConfigKey1", "ConfigValue1");
                    }});
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                    }});
                    put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                }});
            }});
        }};
        deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);

        assertTrue(mainRestarted.get());
        assertTrue(serviceStarted.get());

        // Establish an IPC connection on behalf of the running service
        KernelIPCClientConfig config = getIPCConfigForService("OldService", kernel);
        client = new IPCClientImpl(config);
        configStore = new ConfigStoreImpl(client);
    }

    @AfterEach
    void after() throws IOException {
        if (client != null) {
            client.disconnect();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_validates_config_THEN_deployment_is_successful()
            throws Throwable {
        // Subscribe to config validation on behalf of the running service
        CountDownLatch eventReceivedByClient = new CountDownLatch(1);
        configStore.subscribeToValidateConfiguration((configMap) -> {
            assertThat(configMap, IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
            try {
                configStore.sendConfigurationValidityReport(ConfigurationValidityStatus.VALID, null);
            } catch (ConfigStoreIPCException e) {
            }
            eventReceivedByClient.countDown();
        });

        // Attempt changing the configuration for the running service
        HashMap<String, Object> newConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", kernel.getMain().getServiceConfig().toPOJO());
                put("OldService", new HashMap<String, Object>() {{
                    put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                        put("ConfigKey1", "ConfigValue2");
                    }});
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                    }});
                    put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                }});
            }});
        }};
        DeploymentResult result =
                deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_invalidates_config_THEN_deployment_fails()
            throws Throwable {
        // Subscribe to config validation on behalf of the running service
        CountDownLatch eventReceivedByClient = new CountDownLatch(1);
        configStore.subscribeToValidateConfiguration((configMap) -> {
            assertThat(configMap, IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
            eventReceivedByClient.countDown();
            try {
                configStore.sendConfigurationValidityReport(ConfigurationValidityStatus.INVALID,
                        "I don't like this configuration");
            } catch (ConfigStoreIPCException e) {
            }
        });

        // Attempt changing the configuration for the running service
        HashMap<String, Object> newConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", kernel.getMain().getServiceConfig().toPOJO());
                put("OldService", new HashMap<String, Object>() {{
                    put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                        put("ConfigKey1", "ConfigValue2");
                    }});
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                    }});
                    put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                }});
            }});
        }};
        DeploymentResult result =
                deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        assertTrue(result.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(result.getFailureCause().getMessage() != null && result.getFailureCause().getMessage().contains(
                "Components reported that their to-be-deployed configuration is invalid { name = "
                        + "OldService, message = I don't like this configuration }"));
        assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
    }

    private Deployment createTestDeployment() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .timestamp(System.currentTimeMillis() + 20).failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .componentUpdatePolicy(
                        new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS))
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }
}
