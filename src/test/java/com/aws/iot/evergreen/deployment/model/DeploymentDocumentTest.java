/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import com.amazonaws.arn.Arn;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(EGExtension.class)
public class DeploymentDocumentTest {
    @Test
    void GIVEN_fleet_configuration_with_arn_WHEN_load_deployment_document_THEN_parse_successfully() {
        String configurationArn = Arn.builder().withPartition("aws").withService("gg")
                .withResource("configuration:thing/test:1").build().toString();
        Map<String, Object> configMapA = new HashMap<String, Object>() {{
            put("param1", "value1");
        }};
        Map<String, Object> configMapB = new HashMap<String, Object>() {{
            put("param2", singletonMap("foo", "bar"));
        }};
        FleetConfiguration config =
                FleetConfiguration.builder()
                        .creationTimestamp(0L)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("pkgA", new PackageInfo(true, "1.0.0", configMapA));
                            put("pkgB", new PackageInfo(false, "1.1.0", configMapB));
                        }})
                        .configurationArn(configurationArn)
                        .build();

        DeploymentDocument doc = new DeploymentDocument(config);

        assertEquals(configurationArn, doc.getDeploymentId());
        assertNull(doc.getFailureHandlingPolicy());
        assertEquals(0L, doc.getTimestamp());
        assertThat(doc.getDeploymentPackageConfigurationList(),
                containsInAnyOrder(new DeploymentPackageConfiguration("pkgA", "1.0.0", configMapA),
                        new DeploymentPackageConfiguration("pkgB", "1.1.0", configMapB)));
        assertThat(doc.getRootPackages(), containsInAnyOrder("pkgA"));
        assertEquals("thing/test", doc.getGroupName());
    }
}
