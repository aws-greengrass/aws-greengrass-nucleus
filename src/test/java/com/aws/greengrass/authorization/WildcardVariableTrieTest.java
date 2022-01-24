/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class WildcardVariableTrieTest {
    @Test
    void testGlobWildcardMatching() {
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("abc*xyz*");
        rt.add("*/def");
        assertTrue(rt.matches("abc1234asdxyz456", true));
        assertTrue(rt.matches("abcdxyz", true));
        assertTrue(rt.matches("/def", true));
        assertTrue(rt.matches("12345/def", true));
        assertFalse(rt.matches("abc123xyz456/89", true));
        assertFalse(rt.matches("2/3/def", true));
        assertFalse(rt.matches("", true));

        // Only '*' should match all resources
        WildcardVariableTrie rt1 = new WildcardVariableTrie();
        rt1.add("*");
        rt1.add("*wer");
        assertTrue(rt1.matches("9999/88", true));

        WildcardVariableTrie rt2 = new WildcardVariableTrie();
        rt2.add("a/*/*/d");
        rt2.add("**/abc");
        assertTrue(rt2.matches("a/12/34/d", true));
        assertFalse(rt2.matches("a/2/3/4/d", true));
        assertTrue(rt2.matches("78/abc", true));
        assertFalse(rt2.matches("7/8/abc", true));
    }

    @Test
    void testMQTTMultilevelWildcardMatching() {
        //test MQTT wildcard usages according to MQTT V5.0 spec (https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html)
        // Test Valid usages
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("abc/#");
        rt.add("123/#/xyz/#");
        rt.add("#/zzz/45");
        assertTrue(rt.matches("abc/1/2/3", true));
        assertTrue(rt.matches("abc", true));
        assertTrue(rt.matches("abc/", true));
        assertTrue(rt.matches("abc/4/5/6", true));
        assertTrue(rt.matches("123/#/xyz", true));
        assertTrue(rt.matches("123/#/xyz/1/2/345", true));
        assertFalse(rt.matches("abcd/e/f123/4/5/6", true));
        assertFalse(rt.matches("123/4/5/6/xyz/abc", true));
        assertFalse(rt.matches("12/34/zzz/45", true));
        assertFalse(rt.matches("/zzz/45", true));

        // Only '#' should match all resources
        WildcardVariableTrie rt1 = new WildcardVariableTrie();
        rt1.add("#");
        assertTrue(rt1.matches("asdu76/asdas/23", true));
        assertTrue(rt1.matches("**90io", true));

        WildcardVariableTrie rt2 = new WildcardVariableTrie();
        rt2.add("/#");
        assertFalse(rt2.matches("a/b/c/d", true));
        assertFalse(rt2.matches("asd", true));
        assertTrue(rt2.matches("", true));

        // Test Invalid usages
        WildcardVariableTrie rt3 = new WildcardVariableTrie();
        rt3.add("w/qqq#");
        rt3.add("12/#/");
        rt3.add("##");
        assertFalse(rt3.matches("w/qqq/e/r", true));
        assertFalse(rt3.matches("12/4/5/6", true));
        assertFalse(rt3.matches("abcd", true));

        // Flip allowMQTT and re-test previously authorized resources
        assertFalse(rt.matches("abc/1/2/3", false));
        assertFalse(rt.matches("abc", false));
        assertFalse(rt.matches("abc/", false));
        assertFalse(rt.matches("abc/4/5/6", false));
        assertFalse(rt.matches("123/#/xyz", false));
        assertFalse(rt1.matches("asdu76/asdas/23", false));
        assertFalse(rt1.matches("**90io", false));
        assertFalse(rt2.matches("", false));
    }

    @Test
    void testMQTTSinglelevelWildcardMatching() {
        //test MQTT wildcard usages according to MQTT V5.0 spec (https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html)
        // Test Valid usages
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("abc/+/123");
        rt.add("+/56");
        rt.add("xyz/+/abcd/+/1");
        rt.add("123/fgh/+");
        rt.add("89/+/+/+/90");
        assertTrue(rt.matches("abc/def/123", true));
        assertTrue(rt.matches("xyz/ghj/abcd//1", true));
        assertTrue(rt.matches("123/56", true));
        assertTrue(rt.matches("123/fgh/ert", true));
        assertTrue(rt.matches("89/1/2/3/90", true));
        assertTrue(rt.matches("89/1/2//90", true));
        assertFalse(rt.matches("abc/def/g/123", true));
        assertFalse(rt.matches("89/1/56", true));
        assertFalse(rt.matches("123/fgh/12/34", true));
        assertFalse(rt.matches("89/1/2/90", true));
        assertFalse(rt.matches("89/1/2/3/4/90", true));

        WildcardVariableTrie rt1 = new WildcardVariableTrie();
        rt1.add("+");
        assertTrue(rt1.matches("abc", true));
        assertFalse(rt1.matches("/123", true));
        rt1.add("+/+");
        assertTrue(rt1.matches("/123", true));
        assertFalse(rt1.matches("/123/", true));

        // Test Invalid usages
        WildcardVariableTrie rt2 = new WildcardVariableTrie();
        rt2.add("ax/qwe+/123");
        rt2.add("12/+23");
        rt2.add("/+/++");
        assertFalse(rt2.matches("ax/qwert/123", true));
        assertFalse(rt2.matches("/33/ty", true));
        assertFalse(rt2.matches("12/x23", true));
        assertTrue(rt2.matches("/zz/++", true));

        // Flip allowMQTT and re-test previously authorized resources
        assertFalse(rt.matches("abc/def/123", false));
        assertFalse(rt.matches("xyz/ghj/abcd//1", false));
        assertFalse(rt.matches("123/56", false));
        assertFalse(rt.matches("123/fgh/ert", false));
        assertFalse(rt.matches("89/1/2/3/90", false));
        assertFalse(rt.matches("89/1/2//90", false));
        assertFalse(rt1.matches("abc", false));
        assertFalse(rt1.matches("/123", false));
        assertFalse(rt2.matches("/zz/++", false));

    }

    @Test
    void testMultipleWildcardsMatching() {
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("xyz*/+/*room/#");
        rt.add("12/*#");
        assertTrue(rt.matches("xyzzzz/89/bedroom/light/5", true));
        assertTrue(rt.matches("12/345#", true));
        assertFalse(rt.matches("xyzzzz/89/fan2/bedroom/light/5", true));
        assertFalse(rt.matches("12/45/67", true));
        rt.add("x*/+/#");
        rt.add("a/+/+/#/+");
        assertTrue(rt.matches("xyzzzz/89/fan2/bedroom/light/5", true));
        assertTrue(rt.matches("xyzzz34/matchPlus", true));
        assertTrue(rt.matches("a/b/c/#/d", true));
        assertFalse(rt.matches("xyzzz34", true));
        assertFalse(rt.matches("a/b/c/d/e", true));
        // Add everything to see previous resources authorized now
        rt.add("#");
        assertTrue(rt.matches("xyzzzz/89/fan2/bedroom/light/5", true));
        assertTrue(rt.matches("12/45/67", true));
        assertTrue(rt.matches("xyzzz34", true));
        assertTrue(rt.matches("a/b/c/d/e", true));

        WildcardVariableTrie rt1 = new WildcardVariableTrie();
        rt1.add("2131/#/#/+/ui/#");
        assertFalse(rt1.matches("2131/x/y/z/ui/1", true));
        assertTrue(rt1.matches("2131/#/#/z/ui/1/56", true));
        // Add everything to see previous resources authorized now
        rt1.add("*");
        assertTrue(rt1.matches("2131/x/y/z/ui/1", true));

        WildcardVariableTrie rt2 = new WildcardVariableTrie();
        rt2.add("abc*/#");
        rt2.add("x*/+/#");
        assertTrue(rt2.matches("abc123", true));
        assertTrue(rt2.matches("xcvb/123", true));
    }
}
