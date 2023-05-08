/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.converter;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.aws.greengrass.model.RunWithInfo;
import software.amazon.awssdk.aws.greengrass.model.SystemResourceLimits;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;
import software.amazon.awssdk.utils.ImmutableMap;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

class DeploymentDocumentConverterTest {
    private static final String ROOT_COMPONENT_TO_REMOVE_1 = "componentToRemove1";
    private static final String ROOT_COMPONENT_TO_REMOVE_2 = "componentToRemove2";
    private static final String EXISTING_ROOT_COMPONENT = "newComponent1";
    private static final String NEW_ROOT_COMPONENT = "newComponent2";
    private static final String DEPENDENCY_COMPONENT = "dependency";

    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> ROOT_COMPONENTS_TO_MERGE = new HashMap<String, String>() {{
        put(EXISTING_ROOT_COMPONENT, "2.0.0");
        put(NEW_ROOT_COMPONENT, "2.0.0");
    }};

    private static final Map<String, String> CURRENT_ROOT_COMPONENTS = new HashMap<String, String>() {{
        put(ROOT_COMPONENT_TO_REMOVE_1, "1.0.0");
        put(ROOT_COMPONENT_TO_REMOVE_2, "2.0.0");
        put(EXISTING_ROOT_COMPONENT, "1.0.0");

    }};


    private static final long REQUEST_TIMESTAMP = System.currentTimeMillis();
    private static final String REQUEST_ID = "requestId";


    // Existing: ROOT_COMPONENT_TO_REMOVE_1-1.0.0, ROOT_COMPONENT_TO_REMOVE_2-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
    // To Remove: ROOT_COMPONENT_TO_REMOVE_1, ROOT_COMPONENT_TO_REMOVE_2
    // To Add: NEW_ROOT_COMPONENT-2.0.0
    // To Update: EXISTING_ROOT_COMPONENT-1.0.0 -> 2.0.0
    // Result roots: NEW_ROOT_COMPONENT-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
    @Test
    void GIVEN_Full_Local_Override_Request_config_update_And_Current_Root_WHEN_convert_THEN_Return_expected_Deployment_Document()
            throws Exception {
        String dependencyUpdateConfigString =
                "{ \"MERGE\": { \"Company\": { \"Office\": { \"temperature\": 22 } }, \"path1\": { \"Object2\": { \"key2\": \"val2\" } } }, \"RESET\": [ \"/secret/first\" ] }";
        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();
        updateConfig.put(DEPENDENCY_COMPONENT,
                mapper.readValue(dependencyUpdateConfigString, ConfigurationUpdateOperation.class));

        String existingUpdateConfigString = "{ \"MERGE\": {\"foo\": \"bar\"}}";
        updateConfig.put(EXISTING_ROOT_COMPONENT,
                mapper.readValue(existingUpdateConfigString, ConfigurationUpdateOperation.class));

        Map<String, RunWithInfo> componentToRunWithInfo = new HashMap<>();
        RunWithInfo runWithInfo = new RunWithInfo();
        runWithInfo.setPosixUser("foo:bar");
        runWithInfo.setWindowsUser("testWindowsUser");
        SystemResourceLimits limits = new SystemResourceLimits();
        limits.setMemory(102400L);
        limits.setCpus(1.5);
        runWithInfo.setSystemResourceLimits(limits);
        componentToRunWithInfo.put(NEW_ROOT_COMPONENT, runWithInfo);
        runWithInfo = new RunWithInfo();
        runWithInfo.setPosixUser("1234");
        runWithInfo.setWindowsUser("testWindowsUser2");
        componentToRunWithInfo.put(DEPENDENCY_COMPONENT, runWithInfo);

        // Existing: ROOT_COMPONENT_TO_REMOVE_1-1.0.0, ROOT_COMPONENT_TO_REMOVE_2-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
        // To Remove: ROOT_COMPONENT_TO_REMOVE_1, ROOT_COMPONENT_TO_REMOVE_2
        // To Add: NEW_ROOT_COMPONENT-2.0.0
        // To Update: EXISTING_ROOT_COMPONENT-1.0.0 -> 2.0.0
        // Result roots: NEW_ROOT_COMPONENT-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
        LocalOverrideRequest testRequest =
                LocalOverrideRequest.builder().requestId(REQUEST_ID).requestTimestamp(REQUEST_TIMESTAMP)
                        .componentsToMerge(ROOT_COMPONENTS_TO_MERGE)
                        .componentsToRemove(Arrays.asList(ROOT_COMPONENT_TO_REMOVE_1, ROOT_COMPONENT_TO_REMOVE_2))
                        .configurationUpdate(updateConfig)
                        .componentToRunWithInfo(componentToRunWithInfo)
                        .failureHandlingPolicy(software.amazon.awssdk.aws.greengrass.model.FailureHandlingPolicy.ROLLBACK)
                        .build();

        DeploymentDocument deploymentDocument = DeploymentDocumentConverter
                .convertFromLocalOverrideRequestAndRoot(testRequest, CURRENT_ROOT_COMPONENTS);

        assertThat(deploymentDocument.getFailureHandlingPolicy(), is(FailureHandlingPolicy.ROLLBACK));

        assertThat(deploymentDocument.getDeploymentId(), is(REQUEST_ID));
        assertThat(deploymentDocument.getTimestamp(), is(REQUEST_TIMESTAMP));
        assertThat(deploymentDocument.getRootPackages(),
                is(Arrays.asList(EXISTING_ROOT_COMPONENT, NEW_ROOT_COMPONENT)));

        List<DeploymentPackageConfiguration> deploymentPackageConfigurations =
                deploymentDocument.getDeploymentPackageConfigurationList();

        assertThat(deploymentPackageConfigurations.size(), is(3));

        // verify deploymentConfigs
        DeploymentPackageConfiguration existingRootComponentConfig =
                deploymentPackageConfigurations.stream().filter(e -> e.getPackageName().equals(EXISTING_ROOT_COMPONENT))
                        .findAny().get();

        assertThat(existingRootComponentConfig.getResolvedVersion(), is("2.0.0"));
        assertThat(existingRootComponentConfig.getConfigurationUpdateOperation(),
                is(mapper.readValue(existingUpdateConfigString, ConfigurationUpdateOperation.class)));

        DeploymentPackageConfiguration newRootComponentConfig =
                deploymentPackageConfigurations.stream().filter(e -> e.getPackageName().equals(NEW_ROOT_COMPONENT))
                        .findAny().get();

        assertThat(newRootComponentConfig.getResolvedVersion(), is("2.0.0"));
        assertNull(newRootComponentConfig.getConfigurationUpdateOperation());
        assertEquals("foo:bar", newRootComponentConfig.getRunWith().getPosixUser());
        assertEquals("testWindowsUser", newRootComponentConfig.getRunWith().getWindowsUser());
        assertEquals(1.5, newRootComponentConfig.getRunWith().getSystemResourceLimits().getCpus());
        assertEquals(102400L, newRootComponentConfig.getRunWith().getSystemResourceLimits().getMemory());

        DeploymentPackageConfiguration DependencyComponentConfig =
                deploymentPackageConfigurations.stream().filter(e -> e.getPackageName().equals(DEPENDENCY_COMPONENT))
                        .findAny().get();

        assertEquals(DependencyComponentConfig.getConfigurationUpdateOperation(),
                mapper.readValue(dependencyUpdateConfigString, ConfigurationUpdateOperation.class));
        assertThat(DependencyComponentConfig.getResolvedVersion(), is("*"));
    }

    @Test
    void GIVEN_Full_FCS_Deployment_Config_When_convert_Then_all_fields_are_converted_correctly() throws Exception {
        // GIVEN
        String filename = "FcsDeploymentConfig_Full.json";
        String json = new String(Files.readAllBytes(Paths.get(getClass().getResource(filename).toURI())));

        Configuration resultConfig = mapper.readValue(json, Configuration.class);

        // WHEN
        DeploymentDocument deploymentDocument =
                DeploymentDocumentConverter.convertFromDeploymentConfiguration(resultConfig);

        // THEN
        assertThat(deploymentDocument.getFailureHandlingPolicy(), is(FailureHandlingPolicy.DO_NOTHING));
        assertThat(deploymentDocument.getTimestamp(), is(1604067741583L));
        assertThat(deploymentDocument.getComponentUpdatePolicy().getComponentUpdatePolicyAction(),
                   is(NOTIFY_COMPONENTS));
        assertThat(deploymentDocument.getComponentUpdatePolicy().getTimeout(), is(120));

        assertThat(deploymentDocument.getConfigurationArn(),
                   is("arn:aws:greengrass:us-east-1:698947471564:configuration:thinggroup/SampleGroup:2"));
        assertThat(deploymentDocument.getGroupName(), is("thinggroup/SampleGroup"));
        assertThat(deploymentDocument.getRequiredCapabilities(), equalTo(Arrays.asList("LARGE_CONFIGURATION",
                "ANOTHER_CAPABILITY", "LINUX_RESOURCE_LIMITS")));

        assertThat(deploymentDocument.getDeploymentPackageConfigurationList(), hasSize(2));

        DeploymentPackageConfiguration componentConfiguration =
                deploymentDocument.getDeploymentPackageConfigurationList().get(0);

        assertThat(componentConfiguration.getPackageName(), equalTo("CustomerApp"));
        assertThat(componentConfiguration.getResolvedVersion(), equalTo("1.0.0"));
        assertThat(componentConfiguration.getRunWith(), is(notNullValue()));
        assertThat(componentConfiguration.getRunWith().getPosixUser(), equalTo("foo"));
        assertThat(componentConfiguration.getRunWith().getWindowsUser(), equalTo("bar"));
        assertThat(componentConfiguration.getConfigurationUpdateOperation().getPathsToReset(),
                   equalTo(Arrays.asList("/sampleText", "/path")));
        assertThat(componentConfiguration.getConfigurationUpdateOperation().getValueToMerge(),
                   equalTo(ImmutableMap.of("key", "val")));

        assertEquals(1.5, componentConfiguration.getRunWith().getSystemResourceLimits().getCpus());
        assertEquals(1024000L,
                componentConfiguration.getRunWith().getSystemResourceLimits().getMemory());

        componentConfiguration =
                deploymentDocument.getDeploymentPackageConfigurationList().get(1);

        assertThat(componentConfiguration.getPackageName(), equalTo("CustomerApp2"));
        assertThat(componentConfiguration.getRunWith(), is(notNullValue()));
        assertThat(componentConfiguration.getRunWith().getPosixUser(), is(nullValue()));
        assertThat(componentConfiguration.getRunWith().getWindowsUser(), is(nullValue()));
        assertThat(componentConfiguration.getRunWith().getSystemResourceLimits(), is(nullValue()));
        assertThat(componentConfiguration.getRunWith().hasPosixUserValue(), is(true));
    }

    @Test
    void GIVEN_FCS_Deployment_Config_Missing_Fields_When_convert_Then_all_fields_are_converted_with_defaults()
            throws Exception {
        // GIVEN
        String filename = "FcsDeploymentConfig_Missing_Fields.json";
        String json = new String(Files.readAllBytes(Paths.get(getClass().getResource(filename).toURI())));

        Configuration resultConfig = mapper.readValue(json, Configuration.class);

        // WHEN
        DeploymentDocument deploymentDocument =
                DeploymentDocumentConverter.convertFromDeploymentConfiguration(resultConfig);

        // THEN

        // The following values are from FcsDeploymentConfig_Missing_Fields.json
        assertThat(deploymentDocument.getTimestamp(), is(1604067741583L));
        assertThat(deploymentDocument.getConfigurationArn(),
                   is("arn:aws:greengrass:us-east-1:698947471564:configuration:thinggroup/SampleGroup:2"));
        assertThat(deploymentDocument.getGroupName(), is("thinggroup/SampleGroup"));
        assertThat(deploymentDocument.getRequiredCapabilities(), is(empty()));

        assertThat(deploymentDocument.getDeploymentPackageConfigurationList(), hasSize(1));

        DeploymentPackageConfiguration componentConfiguration =
                deploymentDocument.getDeploymentPackageConfigurationList().get(0);

        assertThat(componentConfiguration.getPackageName(), equalTo("CustomerApp"));
        assertThat(componentConfiguration.getResolvedVersion(), equalTo("1.0.0"));
        assertNull(componentConfiguration.getConfigurationUpdateOperation());

        // The following fields are not provided in the json so default values should be used.
        // Default for FailureHandlingPolicy should be ROLLBACK
        assertThat(deploymentDocument.getFailureHandlingPolicy(), is(FailureHandlingPolicy.ROLLBACK));

        // Default for ComponentUpdatePolicy is NOTIFY_COMPONENTS with 60 sec as timeout
        assertThat(deploymentDocument.getComponentUpdatePolicy().getComponentUpdatePolicyAction(),
                   is(NOTIFY_COMPONENTS));
        assertThat(deploymentDocument.getComponentUpdatePolicy().getTimeout(), is(60));
    }


    @Test
    void GIVEN_FCS_Deployment_Config_Missing_Components_When_convert_is_empty_list_is_returned()
            throws Exception {
        // GIVEN
        String filename = "FcsDeploymentConfig_Missing_Components.json";
        String json = new String(Files.readAllBytes(Paths.get(getClass().getResource(filename).toURI())));

        Configuration resultConfig = mapper.readValue(json, Configuration.class);
        DeploymentDocumentConverter.convertFromDeploymentConfiguration(resultConfig);
        // WHEN
        DeploymentDocument deploymentDocument =
                DeploymentDocumentConverter.convertFromDeploymentConfiguration(resultConfig);

        // THEN

        // The following values are from FcsDeploymentConfig_Missing_Components.json
        assertThat(deploymentDocument.getDeploymentPackageConfigurationList(), empty());

        assertThat(deploymentDocument.getTimestamp(), is(1604067741583L));
        assertThat(deploymentDocument.getConfigurationArn(),
                   is("arn:aws:greengrass:us-east-1:698947471564:configuration:thinggroup/SampleGroup:2"));
        assertThat(deploymentDocument.getGroupName(), is("thinggroup/SampleGroup"));
        assertThat(deploymentDocument.getRequiredCapabilities(), is(empty()));

        // The following fields are not provided in the json so default values should be used.
        // Default for FailureHandlingPolicy should be ROLLBACK
        assertThat(deploymentDocument.getFailureHandlingPolicy(), is(FailureHandlingPolicy.DO_NOTHING));

        // Default for ComponentUpdatePolicy is NOTIFY_COMPONENTS with 60 sec as timeout
        assertThat(deploymentDocument.getComponentUpdatePolicy().getComponentUpdatePolicyAction(),
                   is(NOTIFY_COMPONENTS));
        assertThat(deploymentDocument.getComponentUpdatePolicy().getTimeout(), is(120));
    }

    @Test
    void can_serialize_and_deserialize_deployment_document() throws JsonProcessingException {
        DeploymentDocument doc = DeploymentDocument.builder().configurationValidationPolicy(
                DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(6000).build()).build();

        assertEquals(doc, mapper.readValue(mapper.writeValueAsString(doc), DeploymentDocument.class));
    }
}
