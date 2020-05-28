package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

@NoArgsConstructor
public class LocalDeploymentListener {

    public static final String LOCAL_DEPLOYMENT_ID_PREFIX = "Local-";
    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static Logger logger = LogManager.getLogger(LocalDeploymentListener.class);

    @Inject
    @Named("deploymentsQueue")
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    @Inject
    private Kernel kernel;

    //TODO: LocalDeploymentListener should register with IPC to expose submitLocalDeployment
    //TODO: the interface is not finalized yet, this is more an example.
    /** schedules a deployment with deployment service.
     * @param deploymentDocument document configuration
     * @return true if deployment was scheduled
     */
    public boolean submitLocalDeployment(String deploymentDocument) {
        String localDeploymentIdentifier = LOCAL_DEPLOYMENT_ID_PREFIX + System.currentTimeMillis();
        Deployment deployment = new Deployment(deploymentDocument, DeploymentType.LOCAL, localDeploymentIdentifier);
        if (deploymentsQueue != null && deploymentsQueue.offer(deployment)) {
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, localDeploymentIdentifier)
                    .log("Added local deployment to the queue");
            return true;
        }
        return false;
    }

    /**
     * Retrieves root level components names, component information and runtime parameters.
     * @return serialized ListComponentsResult
     * @throws DeviceConfigurationException failure to serialize ListComponentsResult
     */
    public String listComponents() throws DeviceConfigurationException {
        try {
            List<String> rootComponenetNames = kernel.getMain().getDependencies().keySet().stream()
                    .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                    .map(EvergreenService::getName).collect(Collectors.toList());

            List<ComponentInfo> componentInfo = kernel.orderedDependencies().stream()
                    .filter(service -> !service.getName().equals(kernel.getMain().getName()))
                    .map(service -> {
                        Topic version = service.getConfig().find(VERSION_CONFIG_KEY);
                        Topics parameters = service.getConfig().findTopics(PARAMETERS_CONFIG_KEY);
                        ComponentInfo.ComponentInfoBuilder componentInfoBuilder = ComponentInfo.builder()
                                .packageName(service.getName());
                        if (version != null) {
                            componentInfoBuilder.version(((Semver) version.getOnce()).getValue());
                        }
                        if (parameters != null) {
                            componentInfoBuilder.runtimeParameters(parameters.children.entrySet().stream().collect(
                                    Collectors.toMap(e -> e.getKey(), e -> Coerce.toString(e.getValue()))));

                        }
                        return componentInfoBuilder.build();
                    }).collect(Collectors.toList());

            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new ListComponentsResult(rootComponenetNames, componentInfo));
        } catch (JsonProcessingException e) {
            //TODO: during IPC integration, change this to report internal error
            throw new DeviceConfigurationException("Unable to list components", e);
        }
    }

    //TODO: move this data object to appropriate place during IPC integration.
    /**
     * Data object used to transfer currently running.
     */
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public static class ListComponentsResult {
        @JsonProperty("RootPackages")
        private List<String> rootPackages;

        @JsonProperty("Components")
        private List<ComponentInfo> componentsInfo;

    }

    //TODO: move this data object to appropriate place during IPC integration.
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public static class ComponentInfo {
        @JsonProperty("Name")
        private String packageName;

        @JsonProperty("Version")
        private String version;

        @JsonProperty("RuntimeParameters")
        private Map<String, String> runtimeParameters;

    }

}
