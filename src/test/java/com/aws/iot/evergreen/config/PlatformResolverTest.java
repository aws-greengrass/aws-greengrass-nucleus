/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(EGExtension.class)
public class PlatformResolverTest {
    ObjectMapper mapper = new ObjectMapper();
    private static Method platformResolveInternalMethod;

    @BeforeAll
    public static void setup() throws NoSuchMethodException {
        Class[] cArg = new Class[2];
        cArg[0] = Map.class;
        cArg[1] = Map.class;

        platformResolveInternalMethod = PlatformResolver.class.getDeclaredMethod("resolvePlatform", cArg);
        platformResolveInternalMethod.setAccessible(true);
    }

    private Object invokePlatformResolve(Map<String, Integer> ranks, Map<Object, Object> input)
            throws IllegalAccessException, InvocationTargetException {
        return platformResolveInternalMethod.invoke(null, ranks, input);
    }

    @Test
    public void testPlatformResolve() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("testPlatformResolv.yaml")) {
            assertNotNull(inputStream);
            Object resolvedConfig = invokePlatformResolve(new HashMap<String, Integer>() {{
                                                              put("macos", 99);
                                                              put("linux", 1);
                                                          }}, //forcing platform to resolve to macos
                    (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            try (InputStream inputStream2 = getClass().getResourceAsStream("testPlatformResolvExpected.yaml")) {
                assertNotNull(inputStream2);
                Object expectedResolved = JSON.std.with(new YAMLFactory()).anyFrom(inputStream2);

                String prettyPrintJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedConfig);
                assertEquals(resolvedConfig, expectedResolved, "actual resolved config: \n" + prettyPrintJson);
            }
        }
    }

    @Test
    public void testCurrentPlatform() throws Exception {
        // TODO: move to UAT test
        System.out.println(PlatformResolver.CURRENT_PLATFORM);
    }

}
