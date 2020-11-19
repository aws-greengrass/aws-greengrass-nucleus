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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void WHEN_multi_platform_config_THEN_resolve_successfully() throws Exception {
        String multiPlatformConfigString = "---\n"
                + "key1: \n"
                + "  darwin: darwinVal1\n"
                + "  linux: linuxVal1\n"
                + "  unix: unixVal1\n"
                + "  windows: windowsVal1\n"
                + "key2: \n"
                + "  linux: linuxVal2\n"
                + "  unix: unixVal2\n"
                + "  windows: windowsVal2\n"
                + "key3: \n"
                + "  windows: windowsVal3\n"
                + "  all: allVal3\n"
                + "key4: \n"
                + "  windows: \n"
                + "    subkey4: windowsVal4\n"
                + "key5: \n"
                + "  windows: \n"
                + "    subkey5: windowsVal5\n"
                + "  subkey5: ignoredVal5\n";
        Map<String, Object> multiPlatformConfig = MAPPER.readValue(multiPlatformConfigString, Map.class);

        Set<String> keywords = new HashSet<>(Arrays.asList("darwin", "linux", "unix", "windows", "all"));
        List<String> selectors = Arrays.asList("darwin", "unix", "all");

        String expectedResolvedConfigString = "---\n"
                + "key1: darwinVal1\n"
                + "key2: unixVal2\n"
                + "key3: allVal3\n";

        Map<String, Object> expectedResolvedConfig = MAPPER.readValue(expectedResolvedConfigString, Map.class);

        assertThat(PlatformResolver.filterPlatform(multiPlatformConfig, keywords, selectors).get(),
                equalTo(expectedResolvedConfig));
    }


    @Test
    void WHEN_config_contains_nulls_THEN_nulls_handled_sanely() throws Exception {
        String dataString = "---\n"
                + "key1: null\n"
                + "key2: \n"
                + "  subKey3:  null\n"
                + "  subKey4:  val4\n";

        Map<String, Object> data = MAPPER.readValue(dataString, Map.class);
        Map<String, Object> nested = (Map<String, Object>) data.get("key2");

        assertThat(PlatformResolver.filterPlatform(nested, new HashSet<>(Arrays.asList("subKey3", "subKey4")),
                Arrays.asList("subKey3")).orElse("none"), equalTo("none"));
        assertThat(PlatformResolver.filterPlatform(nested, new HashSet<>(Arrays.asList("subKey3", "subKey4")),
                Arrays.asList("subKey4")).orElse("bad"), equalTo("val4"));
        Object o = PlatformResolver.filterPlatform(data, new HashSet<String>(Arrays.asList("key1", "key2")),
                Arrays.asList("key2")).orElse("bad");
        assertThat(o, isA(Map.class));
        Map<String,Object> m = (Map<String,Object>)o;
        assertThat(m.size(), equalTo(1));
        assertThat(m.get("subKey4"), equalTo("val4"));
    }
}
