package com.aws.iot.evergreen.packagemanager.common;

import com.aws.iot.evergreen.packagemanager.common.Platform.Architecture;
import com.aws.iot.evergreen.packagemanager.common.Platform.OS;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlatformTest {
    ObjectMapper OBJECT_MAPPER =
            new ObjectMapper(new JsonFactory()).configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    public void testSerialize() throws Exception {
        Platform platform = Platform.builder()
                .os(OS.UBUNTU)
                .architecture(Architecture.AMD64)
                .build();

        String expected = "{\"os\":\"ubuntu\",\"architecture\":\"amd64\"}";
        String actual = OBJECT_MAPPER.writeValueAsString(platform);
        assertEquals(expected, actual);
    }

    @Test
    public void testDeserialize() throws Exception {
        // verify character case is ignored
        String testInput = "{\"os\":\"UbUntu\", \"architecture\":\"amd64\"}";
        Platform expected = Platform.builder()
                .os(OS.UBUNTU)
                .architecture(Architecture.AMD64).build();
        assertEquals(expected, OBJECT_MAPPER.readValue(testInput, Platform.class));

        // verify non-provided OS is by default ALL
        testInput = "{\"architecture\":\"Amd64\"}";
        expected = Platform.builder()
                .os(OS.ALL)
                .architecture(Architecture.AMD64).build();
        assertEquals(expected, OBJECT_MAPPER.readValue(testInput, Platform.class));

        // verify "any" keyword in OS
        testInput = "{\"os\":\"any\", \"architecture\":\"amd64\"}";
        expected = Platform.builder()
                .os(OS.ALL)
                .architecture(Architecture.AMD64).build();
        assertEquals(expected, OBJECT_MAPPER.readValue(testInput, Platform.class));

        // verify non-provided Arch is by default ALL
        testInput = "{\"os\":\"ubuntu\"}";
        expected = Platform.builder()
                .os(OS.UBUNTU)
                .architecture(Architecture.ALL).build();
        assertEquals(expected, OBJECT_MAPPER.readValue(testInput, Platform.class));

        // verify "any" keyword in Arch
        testInput = "{\"os\":\"Ubuntu\", \"architecture\":\"any\"}";
        expected = Platform.builder()
                .os(OS.UBUNTU)
                .architecture(Architecture.ALL).build();
        assertEquals(expected, OBJECT_MAPPER.readValue(testInput, Platform.class));
    }
}
