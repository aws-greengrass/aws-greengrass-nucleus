package com.aws.greengrass.deployment;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.greengrass.builtin.services.configstore.exceptions.ValidateEventRegistrationException;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.DynamicConfigurationValidationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DynamicComponentConfigurationValidatorTest {
    private static final String DEFAULT_EXISTING_SERVICE_VERSION = "1.0.0";
    private static final long DEFAULT_EXISTING_NODE_MOD_TIME = 10;
    private static final long DEFAULT_DEPLOYMENT_TIMESTAMP = 100;

    @Mock
    private ConfigStoreIPCAgent configStoreIPCAgent;

    @Mock
    private Kernel kernel;

    @Mock
    private Context context;

    private CompletableFuture<DeploymentResult> deploymentResultFuture;

    private DynamicComponentConfigurationValidator validator;

    @BeforeEach
    void beforeEach() throws Exception {
        lenient().when(kernel.getContext()).thenReturn(context);
        validator = new DynamicComponentConfigurationValidator(kernel, configStoreIPCAgent);
        deploymentResultFuture = new CompletableFuture<>();
    }

    @AfterEach
    void afterEach() throws Exception {
        context.close();
    }

    @Test
    void GIVEN_deployment_changes_service_config_WHEN_service_validates_config_THEN_succeed() throws Exception {
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any())).thenAnswer(invocationOnMock -> {
            CompletableFuture<ConfigurationValidityReport> validityReportFuture = invocationOnMock.getArgument(2);
            validityReportFuture
                    .complete(ConfigurationValidityReport.builder().status(ConfigurationValidityStatus.VALID).build());
            return true;
        });
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertTrue(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        assertFalse(deploymentResultFuture.isDone());
    }

    @Test
    void GIVEN_deployment_changes_service_config_WHEN_service_invalidates_config_THEN_fail_deployment()
            throws Exception {
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any())).thenAnswer(invocationOnMock -> {
            CompletableFuture<ConfigurationValidityReport> validityReportFuture = invocationOnMock.getArgument(2);
            validityReportFuture.complete(
                    ConfigurationValidityReport.builder().status(ConfigurationValidityStatus.INVALID)
                            .message("Proposed configuration is invalid").build());
            return true;
        });
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertFalse(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        DeploymentResult deploymentResult = deploymentResultFuture.get();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, deploymentResult.getDeploymentStatus());
        assertTrue(deploymentResult.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(deploymentResult.getFailureCause().getMessage() != null && deploymentResult.getFailureCause()
                .getMessage().contains(
                        "Components reported that their to-be-deployed configuration is invalid { name = OldService, message = Proposed configuration is invalid }"));
    }

    @Test
    void GIVEN_deployment_changes_service_config_WHEN_service_version_changes_THEN_no_validation_requested()
            throws Exception {
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, "2.0.0");
            }});
        }};
        assertTrue(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, never()).validateConfiguration(any(), any(), any());
        assertFalse(deploymentResultFuture.isDone());
    }

    @Test
    void GIVEN_deployment_has_service_config_WHEN_service_config_is_unchanged_THEN_no_validation_requested()
            throws Exception {
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue1");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertTrue(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, never()).validateConfiguration(any(), any(), any());
        assertFalse(deploymentResultFuture.isDone());
    }

    @Test
    void GIVEN_validation_requested_WHEN_validation_request_times_out_THEN_fail_deployment(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, TimeoutException.class);
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any())).thenReturn(true);
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertFalse(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        DeploymentResult deploymentResult = deploymentResultFuture.get();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, deploymentResult.getDeploymentStatus());
        assertTrue(deploymentResult.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(deploymentResult.getFailureCause().getMessage() != null && deploymentResult.getFailureCause()
                .getMessage().contains("Error while waiting for validation report for one or more components"));
    }

    @Test
    void GIVEN_validation_requested_WHEN_error_while_waiting_for_validation_report_THEN_fail_deployment(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context, "Some unexpected error");
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any())).thenAnswer(invocationOnMock -> {
            CompletableFuture<ConfigurationValidityReport> validityReportFuture = invocationOnMock.getArgument(2);
            validityReportFuture.completeExceptionally(new InterruptedException("Some unexpected error"));
            return true;
        });
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertFalse(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        DeploymentResult deploymentResult = deploymentResultFuture.get();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, deploymentResult.getDeploymentStatus());
        assertTrue(deploymentResult.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(deploymentResult.getFailureCause().getMessage() != null && deploymentResult.getFailureCause()
                .getMessage().contains("Error while waiting for validation report for one or more components"));
    }

    @Test
    void GIVEN_config_validation_needed_WHEN_error_requesting_validation_THEN_fail_deployment()
            throws Exception {
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any()))
                .thenThrow(ValidateEventRegistrationException.class);
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertFalse(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        DeploymentResult deploymentResult = deploymentResultFuture.get();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, deploymentResult.getDeploymentStatus());
        assertTrue(deploymentResult.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(deploymentResult.getFailureCause().getMessage() != null && deploymentResult.getFailureCause()
                .getMessage().contains("Error requesting validation from component OldService"));
    }

    @Test
    void GIVEN_deployment_has_internal_service_WHEN_validating_config_THEN_no_validation_attempted_for_internal_service()
            throws Exception {
        when(configStoreIPCAgent.validateConfiguration(any(), any(), any())).thenAnswer(invocationOnMock -> {
            CompletableFuture<ConfigurationValidityReport> validityReportFuture = invocationOnMock.getArgument(2);
            validityReportFuture
                    .complete(ConfigurationValidityReport.builder().status(ConfigurationValidityStatus.VALID).build());
            return true;
        });
        createMockGenericExternalService("OldService");
        createMockEvergreenService("OldInternalService");

        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
            put("OldInternalService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
            }});
        }};
        assertTrue(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, times(1)).validateConfiguration(any(), any(), any());
        assertFalse(deploymentResultFuture.isDone());
    }

    @Test
    void GIVEN_deployment_has_a_new_service_WHEN_validating_config_THEN_no_validation_attempted_for_new_service()
            throws Exception {
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("NewService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
            put("OldInternalService", new HashMap<String, Object>() {{
                put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                    put("ConfigKey1", "ConfigValue2");
                }});
                put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
            }});
        }};
        assertTrue(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, never()).validateConfiguration(any(), any(), any());
        assertFalse(deploymentResultFuture.isDone());
    }

    @Test
    void GIVEN_new_deployment_WHEN_service_config_format_invalid_THEN_fail_deployment() throws Exception {
        createMockGenericExternalService("OldService");
        HashMap<String, Object> servicesConfig = new HashMap<String, Object>() {{
            put("OldService", "Faulty Proposed Service Config");
        }};
        assertFalse(validator.validate(servicesConfig, createTestDeployment(), deploymentResultFuture));
        verify(configStoreIPCAgent, never()).validateConfiguration(any(), any(), any());
        DeploymentResult deploymentResult = deploymentResultFuture.get();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, deploymentResult.getDeploymentStatus());
        assertTrue(deploymentResult.getFailureCause() instanceof DynamicConfigurationValidationException);
        assertTrue(deploymentResult.getFailureCause().getMessage() != null && deploymentResult.getFailureCause()
                .getMessage().contains("Services config must be a map"));
    }

    private Deployment createTestDeployment() {
        DeploymentDocument doc = new DeploymentDocument();
        doc.setDeploymentId("test_deployment_id");
        doc.setTimestamp(DEFAULT_DEPLOYMENT_TIMESTAMP);
        Deployment deployment = new Deployment();
        deployment.setDeploymentDocumentObj(doc);
        return deployment;
    }

    private GenericExternalService createMockGenericExternalService(String name) throws ServiceLoadException {
        Topic versionConfig = mock(Topic.class);
        lenient().when(versionConfig.toPOJO()).thenReturn(DEFAULT_EXISTING_SERVICE_VERSION);
        lenient().when(versionConfig.getModtime()).thenReturn(DEFAULT_EXISTING_NODE_MOD_TIME);
        Topics componentConfig = mock(Topics.class);
        lenient().when(componentConfig.toPOJO()).thenReturn(Collections.singletonMap("ConfigKey1", "ConfigValue1"));
        lenient().when(componentConfig.getModtime()).thenReturn(DEFAULT_EXISTING_NODE_MOD_TIME);
        Topics serviceConfig = mock(Topics.class);
        lenient().when(serviceConfig.findNode(VERSION_CONFIG_KEY)).thenReturn(versionConfig);
        lenient().when(serviceConfig.findTopics(PARAMETERS_CONFIG_KEY)).thenReturn(componentConfig);
        GenericExternalService service = mock(GenericExternalService.class);
        lenient().when(service.getName()).thenReturn(name);
        lenient().when(service.getServiceConfig()).thenReturn(serviceConfig);
        lenient().when(kernel.locate(name)).thenReturn(service);
        return service;
    }

    private GreengrassService createMockEvergreenService(String name) throws ServiceLoadException {
        GreengrassService service = mock(GreengrassService.class);
        lenient().when(service.getName()).thenReturn(name);
        lenient().when(kernel.locate(name)).thenReturn(service);
        return service;
    }
}
