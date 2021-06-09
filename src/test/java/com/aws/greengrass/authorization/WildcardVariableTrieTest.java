/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class WildcardVariableTrieTest {
    @Test
    void test() {
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("abc*xyz");
        rt.add("xyz");
        rt.add("xyz*");
        rt.add("123*xyz*");
        rt.add("14*xyz");
        rt.add("topic/${iot:ThingName}");
        rt.add("topic/${iot:ThingName}/abc/*");
        rt.add("thisisaplaintopic");

        assertFalse(rt.matches("abc", Collections.emptyMap()));
        assertFalse(rt.matches("abc123", Collections.emptyMap()));
        assertFalse(rt.matches("abc123xyz123", Collections.emptyMap()));
        assertFalse(rt.matches("124xyz", Collections.emptyMap()));
        assertTrue(rt.matches("xyz", Collections.emptyMap()));
        assertTrue(rt.matches("abc123xyz", Collections.emptyMap()));
        assertTrue(rt.matches("abcasdgl9u935ksndgi9xyz", Collections.emptyMap()));
        assertTrue(rt.matches("xyzasdgl9u935ksndgi9xyz", Collections.emptyMap()));
        assertTrue(rt.matches("123abcxyz", Collections.emptyMap()));
        assertTrue(rt.matches("14abcxyz", Collections.emptyMap()));
        Map<String, String> variables = Utils.immutableMap("${iot:ThingName}", "thingName");
        assertFalse(rt.matches("topic/sljkdf", variables));
        assertTrue(rt.matches("topic/thingName", variables));
        assertFalse(rt.matches("topic/thingName/abc", variables));
        assertTrue(rt.matches("topic/thingName/abc/123", variables));
        assertTrue(rt.matches("thisisaplaintopic", variables));
    }
}
