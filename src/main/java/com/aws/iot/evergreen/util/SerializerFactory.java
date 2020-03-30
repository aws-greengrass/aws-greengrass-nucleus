package com.aws.iot.evergreen.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public final class SerializerFactory {
    private static final ObjectMapper RECIPE_SERIALIZER =
            new ObjectMapper(new YAMLFactory()).configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static ObjectMapper getRecipeSerializer() {
        return RECIPE_SERIALIZER;
    }

    private SerializerFactory() {
    }
}
