/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.converter;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class DeploymentDocumentConverterTest {
    private static final String ROOT_COMPONENT_TO_REMOVE_1 = "componentToRemove1";
    private static final String ROOT_COMPONENT_TO_REMOVE_2 = "componentToRemove2";
    private static final String EXISTING_ROOT_COMPONENT = "newComponent1";
    private static final String NEW_ROOT_COMPONENT = "newComponent2";
    private static final String DEPENDENCY_COMPONENT = "dependency";


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

    @Test
    void GIVEN_Full_Local_Override_Request_And_Current_Root_WHEN_convert_THEN_Return_expected_Deployment_Document() {

        Map<String, Map<String, Object>> componentNameToConfig = new HashMap<>();
        componentNameToConfig.put(EXISTING_ROOT_COMPONENT, new HashMap<>());
        componentNameToConfig.get(EXISTING_ROOT_COMPONENT).put("K1", "V1");
        componentNameToConfig.get(EXISTING_ROOT_COMPONENT).put("nested", new HashMap<>());
        ((HashMap) componentNameToConfig.get(EXISTING_ROOT_COMPONENT).get("nested")).put("K2", "V2");

        componentNameToConfig.put(DEPENDENCY_COMPONENT, new HashMap<>());
        componentNameToConfig.get(DEPENDENCY_COMPONENT).put("K3", "V3");


        // Existing: ROOT_COMPONENT_TO_REMOVE_1-1.0.0, ROOT_COMPONENT_TO_REMOVE_2-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
        // To Remove: ROOT_COMPONENT_TO_REMOVE_1, ROOT_COMPONENT_TO_REMOVE_2
        // To Add: NEW_ROOT_COMPONENT-2.0.0
        // To Update: EXISTING_ROOT_COMPONENT-1.0.0 -> 2.0.0
        // Result roots: NEW_ROOT_COMPONENT-2.0.0, EXISTING_ROOT_COMPONENT-2.0.0
        LocalOverrideRequest testRequest =
                LocalOverrideRequest.builder().requestId(REQUEST_ID).requestTimestamp(REQUEST_TIMESTAMP)
                        .componentsToMerge(ROOT_COMPONENTS_TO_MERGE)
                        .componentsToRemove(Arrays.asList(ROOT_COMPONENT_TO_REMOVE_1, ROOT_COMPONENT_TO_REMOVE_2))
                        .componentNameToConfig(componentNameToConfig).build();

        DeploymentDocument deploymentDocument = DeploymentDocumentConverter
                .convertFromLocalOverrideRequestAndRoot(testRequest, CURRENT_ROOT_COMPONENTS);

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
        assertThat(existingRootComponentConfig.getConfiguration(),
                is(componentNameToConfig.get(EXISTING_ROOT_COMPONENT)));

        DeploymentPackageConfiguration newRootComponentConfig =
                deploymentPackageConfigurations.stream().filter(e -> e.getPackageName().equals(NEW_ROOT_COMPONENT))
                        .findAny().get();

        assertThat(newRootComponentConfig.getResolvedVersion(), is("2.0.0"));
        assertThat(newRootComponentConfig.getConfiguration(), is(nullValue()));


        DeploymentPackageConfiguration DependencyComponentConfig =
                deploymentPackageConfigurations.stream().filter(e -> e.getPackageName().equals(DEPENDENCY_COMPONENT))
                        .findAny().get();

        assertThat(DependencyComponentConfig.getResolvedVersion(), is("*"));
        assertThat(DependencyComponentConfig.getConfiguration(), is(componentNameToConfig.get(DEPENDENCY_COMPONENT)));
    }
}