package com.aws.iot.evergreen.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public final class SerializerFactory {
    private static final ObjectMapper RECIPE_SERIALIZER =
            new ObjectMapper(new YAMLFactory()).configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final ObjectMapper JSON_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static ObjectMapper getRecipeSerializer() {
        return RECIPE_SERIALIZER;
    }

    public static ObjectMapper getJsonObjectMapper() {
        return JSON_OBJECT_MAPPER;
    }

    private SerializerFactory() {
    }
}
