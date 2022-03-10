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
class WildcardTrieTest {
    @Test
    void testGlobWildcardMatching() {
        // no wildcards
        WildcardTrie rt = new WildcardTrie();
        rt.add("nowildcard");
        rt.add("nowildcard2");
        assertTrue(rt.matches("nowildcard"));
        assertTrue(rt.matches("nowildcard"));
        assertFalse(rt.matches("topic"));

        // Test wildcards in middle
        rt.add("abc*xy*z");
        assertTrue(rt.matches("abc123xyabc!@#$%^&*()_+-=z" ));
        assertTrue(rt.matches("abcxyz"));
        assertTrue(rt.matches("abcxy7895z"));
        assertTrue(rt.matches("abc123xy90zABCz"));
        assertTrue(rt.matches("abc123xy90zABCxyABCz"));

        assertFalse(rt.matches("ab789xyz123"));
        assertFalse(rt.matches("abc789xy56z123"));
        assertFalse(rt.matches("abc123xy90zABCzz0"));
        assertFalse(rt.matches("abc123yx90z"));
        assertFalse(rt.matches(""));

        // Test multiple terminal points
        rt.add("abc*xy*23");
        assertTrue(rt.matches("abc789xy56z123"));
        rt.add("abc*xy*");
        assertTrue(rt.matches("abc123xy90zABCzz0"));
        rt.add("abc*yx*z");
        assertTrue(rt.matches("abc123yx90z"));

        // Test Edge wildcards
        WildcardTrie rt1 = new WildcardTrie();
        rt1.add("*qwe*90*");

        assertTrue(rt1.matches("abcqwe12390abcde" ));
        assertTrue(rt1.matches("qwe90"));
        assertTrue(rt1.matches("qwe9012"));
        assertTrue(rt1.matches("789qwe-+9qwe-+90ABC"));

        assertFalse(rt1.matches("789qwe-+9A"));
        assertFalse(rt1.matches("ABC89078"));

        rt1.add("*90*");
        assertTrue(rt1.matches("ABC89078"));

        // Only '*' should match all resources
        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("*wer");
        assertFalse(rt2.matches("123werX"));
        rt2.add("*");
        assertTrue(rt2.matches("123werX"));
        assertTrue(rt2.matches("9999/88"));

        WildcardTrie rt3 = new WildcardTrie();
        rt3.add("**/abc");
        assertTrue(rt3.matches("78/abc"));
        assertTrue(rt3.matches("7/8/abc"));
        assertTrue(rt3.matches("78/abc"));
    }
}
