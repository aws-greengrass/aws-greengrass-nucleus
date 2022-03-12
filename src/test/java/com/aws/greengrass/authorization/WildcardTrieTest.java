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
        assertTrue(rt.matchesStandard("nowildcard"));
        assertFalse(rt.matchesStandard("topic"));

        assertTrue(rt.matchesMQTT("nowildcard"));
        assertFalse(rt.matchesMQTT("topic"));

        // Test wildcards in middle
        rt.add("abc*xy*z");
        assertTrue(rt.matchesStandard("abc123xyabc!@/<>#$%^&*()_+-=z" ));
        assertTrue(rt.matchesStandard("abcxyz"));
        assertTrue(rt.matchesStandard("abcxy/ 7895z"));
        assertTrue(rt.matchesStandard("abc123xy90zABCz"));
        assertTrue(rt.matchesStandard("abc123xy90zABCxyABCz"));
        assertFalse(rt.matchesStandard("ab789xyz123"));
        assertFalse(rt.matchesStandard("abc789xy56z123"));
        assertFalse(rt.matchesStandard("abc123xy90zABCzz0"));
        assertFalse(rt.matchesStandard("abc123yx90z"));
        assertFalse(rt.matchesStandard(""));

        assertTrue(rt.matchesMQTT("abc123xyabc!@/<>#$%^&*()_+-=z" ));
        assertTrue(rt.matchesMQTT("abcxyz"));
        assertTrue(rt.matchesMQTT("abcxy/ 7895z"));
        assertTrue(rt.matchesMQTT("abc123xy90zABCz"));
        assertTrue(rt.matchesMQTT("abc123xy90zABCxyABCz"));
        assertFalse(rt.matchesMQTT("ab789xyz123"));
        assertFalse(rt.matchesMQTT("abc789xy56z123"));
        assertFalse(rt.matchesMQTT("abc123xy90zABCzz0"));
        assertFalse(rt.matchesMQTT("abc123yx90z"));
        assertFalse(rt.matchesMQTT(""));

        // Test multiple terminal points
        rt.add("abc*xy*23");
        assertTrue(rt.matchesStandard("abc789xy56z123"));

        assertTrue(rt.matchesMQTT("abc789xy56z123"));

        rt.add("abc*xy*");
        assertTrue(rt.matchesStandard("abc123xy90zABCzz0"));

        assertTrue(rt.matchesMQTT("abc123xy90zABCzz0"));

        rt.add("abc*yx*z");
        assertTrue(rt.matchesStandard("abc123yx90z"));

        assertTrue(rt.matchesMQTT("abc123yx90z"));

        // Test Edge wildcards
        WildcardTrie rt1 = new WildcardTrie();
        rt1.add("*qwe*90*");
        assertTrue(rt1.matchesStandard("abcqwe12390abcde" ));
        assertTrue(rt1.matchesStandard("qwe90"));
        assertTrue(rt1.matchesStandard("qwe9012"));
        assertTrue(rt1.matchesStandard("789qwe-+9qwe-+90ABC"));
        assertFalse(rt1.matchesStandard("789qwe-+9A"));
        assertFalse(rt1.matchesStandard("ABC89078"));

        assertTrue(rt1.matchesMQTT("abcqwe12390abcde" ));
        assertTrue(rt1.matchesMQTT("qwe90"));
        assertTrue(rt1.matchesMQTT("qwe9012"));
        assertTrue(rt1.matchesMQTT("789qwe-+9qwe-+90ABC"));
        assertFalse(rt1.matchesMQTT("789qwe-+9A"));
        assertFalse(rt1.matchesMQTT("ABC89078"));

        rt1.add("*90*");
        assertTrue(rt1.matchesStandard("ABC89078"));

        assertTrue(rt1.matchesMQTT("ABC89078"));

        // Test that wildcard doesn't stop matching at '/'
        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("ab/*/c");
        assertTrue(rt2.matchesStandard("ab/12/c"));
        assertTrue(rt2.matchesStandard("ab/1/3/2/c"));
        assertFalse(rt2.matchesStandard("ab/1/34/2/c/"));

        assertTrue(rt2.matchesMQTT("ab/12/c"));
        assertTrue(rt2.matchesMQTT("ab/1/3/2/c"));
        assertFalse(rt2.matchesMQTT("ab/1/34/2/c/"));

        // Only '*' should match all resources
        WildcardTrie rt3 = new WildcardTrie();
        rt3.add("*wer");
        assertFalse(rt3.matchesStandard("123werX"));

        assertFalse(rt3.matchesMQTT("123werX"));

        rt3.add("*");
        assertTrue(rt3.matchesStandard("123werX"));
        assertTrue(rt3.matchesStandard("9999/88"));

        assertTrue(rt3.matchesMQTT("123werX"));
        assertTrue(rt3.matchesMQTT("9999/88"));

        WildcardTrie rt4 = new WildcardTrie();
        rt4.add("**/abc");
        assertTrue(rt4.matchesStandard("78/abc"));
        assertTrue(rt4.matchesStandard("7/8/abc"));
        assertTrue(rt4.matchesStandard("78/abc"));

        assertTrue(rt4.matchesMQTT("78/abc"));
        assertTrue(rt4.matchesMQTT("7/8/abc"));
        assertTrue(rt4.matchesMQTT("78/abc"));
    }

    @Test
    void testMQTTMultilevelWildcardMatching() {
        //test MQTT wildcard usages according to MQTT V5.0 spec (https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html)
        // Test Valid usages
        WildcardTrie rt = new WildcardTrie();
        rt.add("abc/#");
        assertTrue(rt.matchesMQTT("abc/1/2/3"));
        assertTrue(rt.matchesMQTT("abc"));
        assertTrue(rt.matchesMQTT("abc/"));
        assertTrue(rt.matchesMQTT("abc/4/5/6"));
        assertFalse(rt.matchesMQTT("abcd/e/f123/4/5/6"));

        assertFalse(rt.matchesStandard("abc/1/2/3"));
        assertFalse(rt.matchesStandard("abc"));
        assertFalse(rt.matchesStandard("abc/"));
        assertFalse(rt.matchesStandard("abc/4/5/6"));
        assertFalse(rt.matchesStandard("abcd/e/f123/4/5/6"));
        assertTrue(rt.matchesStandard("abc/#"));

        rt.add("123/#/xyz/#");
        assertTrue(rt.matchesMQTT("123/#/xyz"));
        assertTrue(rt.matchesMQTT("123/#/xyz/1/2/345"));
        assertFalse(rt.matchesMQTT("123/4/5/6/xyz/abc"));
        assertFalse(rt.matchesMQTT("12/34/zzz/45"));

        assertFalse(rt.matchesStandard("123/#/xyz"));
        assertFalse(rt.matchesStandard("123/#/xyz/1/2/345"));
        assertFalse(rt.matchesStandard("123/4/5/6/xyz/abc"));
        assertFalse(rt.matchesStandard("12/34/zzz/45"));
        assertTrue(rt.matchesStandard("123/#/xyz/#"));

        rt.add("#/zzz/45");
        assertFalse(rt.matchesMQTT("x/zzz/45"));
        assertTrue(rt.matchesMQTT("#/zzz/45"));

        assertTrue(rt.matchesStandard("#/zzz/45"));

        // Only '#' should match all resources
        WildcardTrie rt1 = new WildcardTrie();
        rt1.add("#");
        assertTrue(rt1.matchesMQTT("asdu76/asdas/23"));
        assertTrue(rt1.matchesMQTT("**90io"));

        assertFalse(rt1.matchesStandard("asdu76/asdas/23"));
        assertFalse(rt1.matchesStandard("**90io"));
        assertTrue(rt1.matchesStandard("#"));

        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("/#");
        assertTrue(rt2.matchesMQTT(""));
        assertTrue(rt2.matchesMQTT("/a/b/c/d/e"));
        assertFalse(rt2.matchesMQTT("a/b/c/d"));
        assertFalse(rt2.matchesMQTT("asd"));

        assertFalse(rt2.matchesStandard(""));
        assertFalse(rt2.matchesStandard("/a/b/c/d/e"));
        assertFalse(rt2.matchesStandard("a/b/c/d"));
        assertFalse(rt2.matchesStandard("asd"));
        assertTrue(rt2.matchesStandard("/#"));


        // Test Invalid usages
        WildcardTrie rt3 = new WildcardTrie();
        rt3.add("w/qqq#");
        assertFalse(rt3.matchesMQTT("w/qqq/e/r"));
        assertTrue(rt3.matchesMQTT("w/qqq#"));

        assertFalse(rt3.matchesStandard("w/qqq/e/r"));
        assertTrue(rt3.matchesStandard("w/qqq#"));

        rt3.add("12/#/");
        assertFalse(rt3.matchesMQTT("12/4/5/6"));
        assertTrue(rt3.matchesMQTT("12/#/"));

        assertFalse(rt3.matchesStandard("12/4/5/6"));
        assertTrue(rt3.matchesStandard("12/#/"));

        rt3.add("##");
        assertFalse(rt3.matchesMQTT("abcd"));
        assertTrue(rt3.matchesMQTT("##"));

        assertFalse(rt3.matchesStandard("abcd"));
        assertTrue(rt3.matchesStandard("##"));
    }

    @Test
    void testMQTTSinglelevelWildcardMatching() {
        //test MQTT wildcard usages according to MQTT V5.0 spec (https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html)
        // Test Valid usages
        WildcardTrie rt = new WildcardTrie();
        rt.add("abc/+/123");
        assertTrue(rt.matchesMQTT("abc/def/123"));
        assertFalse(rt.matchesMQTT("abc/def/g/123"));

        assertFalse(rt.matchesStandard("abc/def/123"));
        assertFalse(rt.matchesStandard("abc/def/g/123"));
        assertTrue(rt.matchesStandard("abc/+/123"));

        rt.add("+/56");
        assertTrue(rt.matchesMQTT("123/56"));
        assertFalse(rt.matchesMQTT("89/1/56"));

        assertFalse(rt.matchesStandard("123/56"));
        assertFalse(rt.matchesStandard("89/1/56"));
        assertTrue(rt.matchesStandard("+/56"));

        rt.add("xyz/+/abcd/+/1");
        assertTrue(rt.matchesMQTT("xyz/ghj/abcd//1"));

        assertFalse(rt.matchesStandard("xyz/ghj/abcd//1"));

        rt.add("123/fgh/+");
        assertTrue(rt.matchesMQTT("123/fgh/ert"));
        assertFalse(rt.matchesMQTT("123/fgh/12/34"));

        assertFalse(rt.matchesStandard("123/fgh/ert"));
        assertFalse(rt.matchesStandard("123/fgh/12/34"));
        assertTrue(rt.matchesStandard("123/fgh/+"));

        rt.add("89/+/+/+/90");
        assertTrue(rt.matchesMQTT("89/1/2/3/90"));
        assertTrue(rt.matchesMQTT("89/1/2//90"));
        assertFalse(rt.matchesMQTT("89/1/2/90"));
        assertFalse(rt.matchesMQTT("89/1/2/3/4/90"));

        assertFalse(rt.matchesStandard("89/1/2/3/90"));
        assertFalse(rt.matchesStandard("89/1/2//90"));
        assertFalse(rt.matchesStandard("89/1/2/90"));
        assertFalse(rt.matchesStandard("89/1/2/3/4/90"));
        assertTrue(rt.matchesStandard("89/+/+/+/90"));

        WildcardTrie rt1 = new WildcardTrie();
        rt1.add("+");
        assertTrue(rt1.matchesMQTT("abc"));
        assertFalse(rt1.matchesMQTT("/123"));

        assertFalse(rt1.matchesStandard("abc"));
        assertTrue(rt1.matchesStandard("+"));

        rt1.add("+/+");
        assertTrue(rt1.matchesMQTT("/123"));
        assertFalse(rt1.matchesMQTT("/123/"));

        assertFalse(rt1.matchesStandard("/123"));
        assertTrue(rt1.matchesStandard("+/+"));

        // Test Invalid usages
        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("ax/qwe+/123");
        assertFalse(rt2.matchesMQTT("ax/qwert/123"));
        assertTrue(rt2.matchesMQTT("ax/qwe+/123"));

        assertFalse(rt2.matchesStandard("ax/qwert/123"));
        assertTrue(rt2.matchesStandard("ax/qwe+/123"));

        rt2.add("12/+23");
        assertFalse(rt2.matchesMQTT("12/x23"));

        assertFalse(rt2.matchesStandard("12/x23"));
        assertTrue(rt2.matchesStandard("12/+23"));

        rt2.add("/+/++");
        assertFalse(rt2.matchesMQTT("/33/ty"));
        assertTrue(rt2.matchesMQTT("/zz/++"));

        assertFalse(rt2.matchesStandard("/33/ty"));
        assertFalse(rt2.matchesStandard("/zz/++"));
    }

    @Test
    void testMultipleWildcardsMatching() {
        WildcardTrie rt = new WildcardTrie();
        rt.add("xyz*/+/*room/#");
        assertTrue(rt.matchesMQTT("xyzzzz/89/bedroom/light/5"));
        assertTrue(rt.matchesMQTT("xyzzzz/89/fan2/bedroom/light/5"));

        assertFalse(rt.matchesStandard("xyzzzz/89/bedroom/light/5"));
        assertFalse(rt.matchesStandard("xyzzzz/89/fan2/bedroom/light/5"));
        assertTrue(rt.matchesStandard("xyzzzz/+/bedroom/#"));

        rt.add("12/*#");
        assertTrue(rt.matchesMQTT("12/345#"));
        assertTrue(rt.matchesMQTT("12/34/5#"));
        assertFalse(rt.matchesMQTT("12/45/67"));

        assertTrue(rt.matchesStandard("12/345#"));
        assertTrue(rt.matchesStandard("12/34/5#"));
        assertFalse(rt.matchesStandard("12/45/67"));

        rt.add("x*ui/+/#");
        assertTrue(rt.matchesMQTT("xyzzzzui/89/fan2/bedroom/light/5"));
        assertTrue(rt.matchesMQTT("xyz/zz34ui/matchPlus/23"));
        assertFalse(rt.matchesMQTT("xyz/zz34/matchPlus/23"));
        assertFalse(rt.matchesMQTT("xyzzz34"));

        assertFalse(rt.matchesStandard("xyzzzzui/89/fan2/bedroom/light/5"));
        assertFalse(rt.matchesStandard("xyz/zz34ui/matchPlus/23"));
        assertFalse(rt.matchesStandard("xyzzz34"));
        assertTrue(rt.matchesStandard("xcvui/+/#"));

        rt.add("a/+/+/#/+");
        assertTrue(rt.matchesMQTT("a/b/c/#/d"));
        assertFalse(rt.matchesMQTT("a/b/c/d/e"));

        assertTrue(rt.matchesStandard("a/+/+/#/+"));

        // Add everything to see previous resources authorized now
        rt.add("#");
        assertTrue(rt.matchesMQTT("xyzzzz/89/fan2/bedroom/light/5"));
        assertTrue(rt.matchesMQTT("12/45/67"));
        assertTrue(rt.matchesMQTT("xyzzz34"));
        assertTrue(rt.matchesMQTT("a/b/c/d/e"));

        assertFalse(rt.matchesStandard("xyzzzz/89/fan2/bedroom/light/5"));
        assertFalse(rt.matchesStandard("12/45/67"));
        assertFalse(rt.matchesStandard("xyzzz34"));
        assertFalse(rt.matchesStandard("a/b/c/d/e"));

        WildcardTrie rt1 = new WildcardTrie();
        rt1.add("2131/#/#/+/ui/#");
        assertFalse(rt1.matchesMQTT("2131/x/y/z/ui/1"));
        assertTrue(rt1.matchesMQTT("2131/#/#/z/ui/1/56"));

        // Add everything to see previous resources authorized now
        rt1.add("*");
        assertTrue(rt1.matchesMQTT("2131/x/y/z/ui/1"));

        assertTrue(rt1.matchesStandard("2131/x/y/z/ui/1"));

        WildcardTrie rt2 = new WildcardTrie();
        rt2.add("abc*/#");
        assertTrue(rt2.matchesMQTT("abc123"));

        rt2.add("x*/+/#");
        assertTrue(rt2.matchesMQTT("xcvb/123"));
        assertTrue(rt2.matchesMQTT("xcvb/123/4/5"));

        WildcardTrie rt3 = new WildcardTrie();
        rt3.add("a/*bc/#");
        assertTrue(rt3.matchesMQTT("a/xyzbc"));
        assertTrue(rt3.matchesMQTT("a/xyzbc/"));
        assertTrue(rt3.matchesMQTT("a/xyzbc/1/2"));
        assertTrue(rt3.matchesMQTT("a/xyzbcnt/bc"));
        assertTrue(rt3.matchesMQTT("a/xyzbcnt/bc/"));
        assertTrue(rt3.matchesMQTT("a/xyzbc/nt/bc"));

        assertFalse(rt3.matchesStandard("a/xyzbc"));
        assertFalse(rt3.matchesStandard("a/xyzbc/"));
        assertFalse(rt3.matchesStandard("a/xyzbc/1/2"));
        assertFalse(rt3.matchesStandard("a/xyzbcnt/bc"));
        assertFalse(rt3.matchesStandard("a/xyzbcnt/bc/"));
        assertFalse(rt3.matchesStandard("a/xyzbc/nt/bc"));
        assertTrue(rt3.matchesStandard("a/xyzbc/nt/bc/#"));

        WildcardTrie rt4 = new WildcardTrie();
        rt4.add("a/+/#");
        assertTrue(rt4.matchesMQTT("a/xyz"));
        assertTrue(rt4.matchesMQTT("a/xyz/"));
        assertTrue(rt4.matchesMQTT("a/xyz/nt"));
        assertFalse(rt4.matchesMQTT("a"));
        assertTrue(rt4.matchesMQTT("a/"));
    }
}
