/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private String deploymentId;

    private List<DeploymentPackageConfiguration> deploymentPackageConfigurationList;

    private List<String> requiredCapabilities;

    private String groupName;

    @Setter
    private Long timestamp;

    @Builder.Default
    private FailureHandlingPolicy failureHandlingPolicy = FailureHandlingPolicy.ROLLBACK;

    @Builder.Default
    private ComponentUpdatePolicy componentUpdatePolicy = new ComponentUpdatePolicy();

    @Builder.Default
    @JsonSerialize(using = SDKSerializer.class)
    @JsonDeserialize(converter = SDKDeserializer.class)
    private DeploymentConfigurationValidationPolicy configurationValidationPolicy =
            DeploymentConfigurationValidationPolicy.builder().build();

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
                .map(DeploymentPackageConfiguration::getName).collect(Collectors.toList());
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
