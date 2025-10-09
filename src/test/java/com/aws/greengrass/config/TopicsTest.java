/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class TopicsTest {

    private Context context;

    @BeforeEach()
    void beforeEach() {
        context = new Context();
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
    }

    @Test
    void Given_config_Then_compare_root_topics() throws Exception {
        Topics topicsA = new Topics(context, null, null);
        Topics topicsB = new Topics(context, null, null);
        assertTrue(Topics.compareChildren(topicsA, topicsB));
        topicsA = new Topics(context, "root", null);
        assertFalse(Topics.compareChildren(topicsA, topicsB));
        topicsB = new Topics(context, "root", null);
        assertTrue(Topics.compareChildren(topicsA, topicsB));

        Map<String, Object> topicsMap =
                ConfigPlatformResolver.resolvePlatformMap(getClass().getResource("topicsConfig.yaml"));
        UpdateBehaviorTree behavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, 0);
        topicsA.updateFromMap(topicsMap, behavior);
        topicsB.updateFromMap(topicsMap, behavior);
        assertTrue(Topics.compareChildren(topicsA, topicsB));

        String config = "config";
        String diff = "diff";
        topicsB.find(config, diff).withValue(5);
        assertFalse(Topics.compareChildren(topicsA, topicsB));
        topicsB.find(config, diff).withValue(6);
        assertTrue(Topics.compareChildren(topicsA, topicsB));

        topicsB.find(config, diff).remove();
        assertFalse(Topics.compareChildren(topicsA, topicsB));
        topicsA.find(config, diff).remove();
        assertTrue(Topics.compareChildren(topicsA, topicsB));
    }
}
