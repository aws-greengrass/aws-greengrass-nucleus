package com.aws.iot.evergreen.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public final class Serializer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    static {
        OBJECT_MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
