/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PlatformResolverTest {

    private static final ObjectMapper MAPPER = new YAMLMapper();

    @Test
    void testCurrentPlatform() throws Exception {
        // GG_NEEDS_REVIEW: TODO: move to UAT
        PlatformResolver platformResolver = new PlatformResolver(null);
        System.out.println(platformResolver.getCurrentPlatform());
    }

    @Test
    void WHEN_platform_override_THEN_override_should_take_place() throws Exception {
        String platformOverrideConfig = "---\n"
                + "architecture: fooArch\n"
                + "key1: val1\n";

        Map<String, Object> platformOverrideMap = MAPPER.readValue(platformOverrideConfig, Map.class);

        try (Context context = new Context()) {
            Topics platformOverrideTopics = new Topics(context, DeviceConfiguration.PLATFORM_OVERRIDE_TOPIC, null);
            platformOverrideTopics.updateFromMap(platformOverrideMap,
                    new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, 1));

            DeviceConfiguration deviceConfiguration = mock(DeviceConfiguration.class);
            when(deviceConfiguration.getPlatformOverrideTopic()).thenReturn(platformOverrideTopics);

            PlatformResolver platformResolver = new PlatformResolver(deviceConfiguration);
            Map<String, String> currentPlatform = platformResolver.getCurrentPlatform();
            assertThat(currentPlatform.get("key1"), equalTo("val1"));
            assertThat(currentPlatform.get(PlatformResolver.ARCHITECTURE_KEY), equalTo("fooArch"));

            // assert that non-overridden platform attributes exist
            assertThat(currentPlatform.containsKey(PlatformResolver.OS_KEY), equalTo(true));
        }
    }

    @Test
    void WHEN_config_contains_nulls_THEN_nulls_handled_sanely() {
        Map<String, Object> nested = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("a", null);
        data.put("b", nested);
        nested.put("c", null);
        nested.put("d", "e");

        assertThat(PlatformResolver.filterPlatform(nested, new HashSet<String>(Arrays.asList("c", "d")),
                Arrays.asList("c")).orElse("none"), equalTo("none"));
        assertThat(PlatformResolver.filterPlatform(nested, new HashSet<String>(Arrays.asList("c", "d")),
                Arrays.asList("d")).orElse("bad"), equalTo("e"));
        Object o = PlatformResolver.filterPlatform(data, new HashSet<String>(Arrays.asList("a", "b")),
                Arrays.asList("b")).orElse("bad");
        assertThat(o, isA(Map.class));
        Map<String,Object> m = (Map<String,Object>)o;
        assertThat(m.size(), equalTo(1));
        assertThat(m.get("d"), equalTo("e"));
    }
}
