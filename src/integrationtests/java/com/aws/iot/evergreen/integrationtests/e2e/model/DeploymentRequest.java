package com.aws.iot.evergreen.integrationtests.e2e.model;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import software.amazon.awssdk.services.iot.model.AbortConfig;
import software.amazon.awssdk.services.iot.model.TimeoutConfig;
import software.amazon.awssdk.services.iot.model.JobExecutionsRolloutConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

//TODO: This class should be replaced by cloud deployment service model.

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@ToString
public class DeploymentRequest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @JsonProperty("targetArn")
    private String targetArn;

    @JsonProperty("description")
    private String description;

    // When deploy to a thing group, runtimeSpecification stores the iot job document for the deployment.
    // WHen deploy to a single thing, runtimeSpecification stores the desired shadow of the device.
    @JsonProperty("runtimeSpecification")
    private String runtimeSpecification;

    @JsonProperty("iotJobConfiguratons")
    private IotJobConfigurations iotJobConfigurations;

    /**
     * Construct DeploymentRequest from DeploymentDocument.
     * @param targetArn thingArn or thingGroupArn
     * @param description description of the deployment
     * @param doc DeploymentDocument
     * @param jobConfigurations iot job configuration for thing group deployment
     * @throws JsonProcessingException when fail to serialize doc
     */
    public DeploymentRequest(String targetArn, String description,
                             DeploymentDocument doc, IotJobConfigurations jobConfigurations)
            throws JsonProcessingException {
        this.targetArn = targetArn;
        this.description = description;

        this.runtimeSpecification = mapper.writeValueAsString(doc);
        this.iotJobConfigurations = jobConfigurations;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    public static class IotJobConfigurations {
        @JsonProperty("timeoutConfig")
        private Map<String, TimeoutConfig> timeoutConfig;

        @JsonProperty("jobExecutionsRolloutConfig")
        private Map<String, JobExecutionsRolloutConfig> jobExecutionsRolloutConfig;

        @JsonProperty("abortConfig")
        private Map<String, AbortConfig> abortConfig;
    }
}
