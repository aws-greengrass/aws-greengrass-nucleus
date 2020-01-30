/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static com.aws.iot.evergreen.util.Coerce.toInt;
import static com.fasterxml.jackson.jr.ob.JSON.Feature.PRETTY_PRINT_OUTPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ConfigurationTest {
    Configuration config = new Configuration(new Context());
    int prev = 0;

    //    @Test
    public void T1() {
        config.lookup("v").validate((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("v").setValue(0, 42);
        config.lookup("v").setValue(10, 43);
        config.lookup("v").setValue(3, -1);
        config.lookup("v").setValue(20, 44);
        assertEquals(44, config.lookup("v").getOnce());
        assertEquals("v:44", config.lookup("v").toString());
    }

    //    @Test
    public void T2() {
        config.lookup("x", "y").validate((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("x", "y").setValue(0, 42);
        config.lookup("x", "y").setValue(10, 43);
        config.lookup("x", "y").setValue(3, -1);
        config.lookup("x", "y").setValue(20, 44);
        assertEquals(44, toInt(config.lookup("x", "y")));
        assertEquals("x.y:44", config.lookup("x", "y").toString());
    }

    //    @Test
    public void T3() {
        config.lookup("x", "z").validate((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("x", "z").setValue(0, 42);
        config.lookup("x", "z").setValue(10, 43);
        config.lookup("x", "z").setValue(3, -1);
        config.lookup("x", "z").setValue(20, 44);
        assertEquals(44, toInt(config.lookup("x", "z")));
        assertEquals("x.z:44", config.lookup("x", "z").toString());
    }

    @Test
    public void T4() {
        T1();
        T2();
        T3();
        config.lookup("x", "a").setValue(20, "hello");
        config.lookup("x", "b").setValue(20, true);
        config.lookup("x", "c").setValue(20, Math.PI);
        config.lookup("x", "d").setValue(20, System.currentTimeMillis());
        Path p = Paths.get("/tmp/c.log");
        ConfigurationWriter.dump(config, p);
        assertEquals(config.getRoot(), config.getRoot());
        try {
            Configuration c2 = ConfigurationReader.createFromTLog(config.context, p);
            //            System.out.println(c2.hashCode() + " " + config.hashCode());
            //            System.out.println("Read: " + deepToString(c2.getRoot(), 99));
            assertEquals(44, c2.lookup("x", "z").getOnce());
            assertEquals(config, c2);
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
            fail();
        }
        //        config.lookupTopics("services").forEach(s -> System.out.println("Found service " + s.name));
        Topic nv = config.lookup("number");
    }

    @Test
    public void hmm() throws Throwable {
        Configuration testConfig = new Configuration(new Context());
        try (InputStream inputStream = getClass().getResourceAsStream("test.yaml")) {
            assertNotNull(inputStream);
            //            System.out.println("resource: " + deepToString(inputStream, 200) + "\n\t" + getClass()
            //            .getName());
            //            dump(config,"Before");
            testConfig.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));
            //            dump(config,"After");
            Topics platforms = testConfig.findTopics("platforms");
            //            platforms.forEachTopicSet(n -> System.out.println(n.name));

            Topic testValue = testConfig.lookup("number");
            testValue.validate((nv, ov) -> {
                int v = Coerce.toInt(nv);
                if (v < 0) {
                    v = 0;
                }
                if (v > 100) {
                    v = 100;
                }
                return v;
            });
            testValue.setValue("53");
            assertEquals(53, testValue.getOnce());
            testValue.setValue(-10);
            assertEquals(0, testValue.getOnce());
            StringWriter sw = new StringWriter();
            JSON.std.with(PRETTY_PRINT_OUTPUT).with(new YAMLFactory()).write(testConfig.toPOJO(), sw);
            String tc = sw.toString();
            assertTrue(tc.contains("all: \"{platform.invoke} {name}\""));
            assertTrue(tc.contains("requires: \"greenlake\""));
        }
    }

    void dump(Configuration c, String title) {
        System.out.println("______________\n" + title);
        c.deepForEachTopic(t -> System.out.println(t));
    }
}
