/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.Converter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class to model the deployment configuration coming from cloud, local, or any other sources that can trigger a
 * deployment.
 *
 * <p>JSON Annotations are only in tests to easily generate this model from a JSON file. They are not part of business
 * logic.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class DeploymentDocument {

    @JsonProperty("DeploymentId")
    private String deploymentId;

    @JsonProperty("ConfigurationArn")
    private String configurationArn;

    @JsonProperty("Packages")
    private List<DeploymentPackageConfiguration> deploymentPackageConfigurationList;

    @JsonProperty("RequiredCapabilities")
    private List<String> requiredCapabilities;

    @JsonProperty("GroupName")
    private String groupName;

    @JsonProperty("OnBehalfOf")
    private String onBehalfOf;

    @JsonProperty("ParentGroupName")
    private String parentGroupName;

    @Setter
    @JsonProperty("Timestamp")
    private Long timestamp;

    @JsonProperty("FailureHandlingPolicy")
    @Builder.Default
    private FailureHandlingPolicy failureHandlingPolicy = FailureHandlingPolicy.ROLLBACK;

    @JsonProperty("ComponentUpdatePolicy")
    @Builder.Default
    private ComponentUpdatePolicy componentUpdatePolicy = new ComponentUpdatePolicy();

    @JsonProperty("ConfigurationValidationPolicy")
    @Builder.Default
    @JsonSerialize(using = SDKSerializer.class)
    @JsonDeserialize(converter = SDKDeserializer.class)
    private DeploymentConfigurationValidationPolicy configurationValidationPolicy =
            DeploymentConfigurationValidationPolicy.builder().build();


    /**
     * For sub-group deployments root group name is used otherwise group name.
     *
     * @return if available root group name, otherwise group name
     */
    @JsonGetter("GroupName")
    public String getGroupName() {
        return Utils.isEmpty(onBehalfOf) ? groupName : onBehalfOf;
    }

    /**
     * Get a list of root component names from the deploymentPackageConfigurationList.
     *
     * @return list of root component names.
     */
    @JsonIgnore
    public List<String> getRootPackages() {
        if (deploymentPackageConfigurationList == null || deploymentPackageConfigurationList.isEmpty()) {
            return Collections.emptyList();
        }
        return deploymentPackageConfigurationList.stream().filter(DeploymentPackageConfiguration::isRootComponent)
                .map(DeploymentPackageConfiguration::getPackageName).collect(Collectors.toList());
    }

    // Custom serializer for AWS SDK model since Jackson can't figure it out itself
    private static class SDKSerializer extends JsonSerializer {
        SDKSerializer() {
            super();
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            HashMap<String, Object> temp = new HashMap<>();
            SdkPojo pojoValue = (SdkPojo) value;
            pojoValue.sdkFields().forEach(f -> temp.put(f.locationName(), f.getValueOrDefault(value)));
            gen.writeObject(temp);
        }
    }

    private static class SDKDeserializer implements
            Converter<Map<String, Object>, DeploymentConfigurationValidationPolicy> {

        @Override
        public DeploymentConfigurationValidationPolicy convert(Map<String, Object> value) {
            DeploymentConfigurationValidationPolicy.Builder obj = DeploymentConfigurationValidationPolicy.builder();
            obj.sdkFields().forEach(f -> f.set(obj, value.get(f.locationName())));
            return obj.build();
        }

        @Override
        public JavaType getInputType(TypeFactory typeFactory) {
            return typeFactory.constructMapType(Map.class, String.class, Object.class);
        }

        @Override
        public JavaType getOutputType(TypeFactory typeFactory) {
            return typeFactory.constructType(DeploymentConfigurationValidationPolicy.class);
        }
    }
}
