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

        // Test that wildcard doesn't stop matching at '/'
        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("ab/*/c");

        assertTrue(rt2.matches("ab/12/c"));
        assertTrue(rt2.matches("ab/1/3/2/c"));

        assertFalse(rt2.matches("ab/1/34/2/c/"));

        // Only '*' should match all resources
        WildcardTrie rt3 = new WildcardTrie();
        rt3.add("*wer");
        assertFalse(rt3.matches("123werX"));
        rt3.add("*");
        assertTrue(rt3.matches("123werX"));
        assertTrue(rt3.matches("9999/88"));

        WildcardTrie rt4 = new WildcardTrie();
        rt4.add("**/abc");
        assertTrue(rt4.matches("78/abc"));
        assertTrue(rt4.matches("7/8/abc"));
        assertTrue(rt4.matches("78/abc"));
    }
}
