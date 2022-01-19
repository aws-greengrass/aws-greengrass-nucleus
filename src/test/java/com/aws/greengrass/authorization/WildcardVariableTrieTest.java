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
    void test() {
        //test '*' functionality
        WildcardVariableTrie rt = new WildcardVariableTrie();
        rt.add("abc*xyz*");
        rt.add("*def");

        assertTrue(rt.matches("abc123asdxyz456"));
        assertFalse(rt.matches("abc123xyz456/89"));
        assertTrue(rt.matches("def"));
        assertTrue(rt.matches("12345def"));
        assertFalse(rt.matches(""));

        // Only '*' should match all resources
        rt.add("*");
        assertTrue(rt.matches("9999/88"));

        //test '#' functionality
        WildcardVariableTrie rt2 = new WildcardVariableTrie();
        rt2.add("abc/#");
        rt2.add("abc*123/#");
        rt2.add("123/#/xyz");

        assertTrue(rt2.matches("abc/1/2/3"));
        assertTrue(rt2.matches("abcdef123/4/5/6"));
        assertFalse(rt2.matches("abcd/e/f123/4/5/6"));
        assertFalse(rt2.matches("123/4/5/6/xyz"));

        //test '+' functionality
        WildcardVariableTrie rt3 = new WildcardVariableTrie();
        rt3.add("abc/+/123");
        rt3.add("+/56");
        rt3.add("xyz/+/abcd/+/1");

        assertTrue(rt3.matches("abc/def/123"));
        assertFalse(rt3.matches("abc/def/g/123"));
        assertFalse(rt3.matches("abc/def/g/123"));
        assertTrue(rt3.matches("xyz/ghj/abcd//1"));

        //test multiple wildcards
        WildcardVariableTrie rt5 = new WildcardVariableTrie();
        rt5.add("xyz*/+/*room/#");

        assertTrue(rt5.matches("xyzzzz/89/bedroom/light/5"));
        assertFalse(rt5.matches("xyzzzz/89/fan2/bedroom/light/5"));

        rt5.add("xyz*/+/#");
        assertTrue(rt5.matches("xyzzzz/89/fan2/bedroom/light/5"));
    }
}
