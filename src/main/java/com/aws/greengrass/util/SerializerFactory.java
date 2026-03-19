/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class SerializerFactory {

    // most of our use cases need to be fail safe on unknowns, rather than throwing exceptions
    private static final ObjectMapper FAIL_SAFE_JSON_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper();

    private static final ObjectMapper STRICT_JSON_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static final ObjectMapper CASE_INSENSITIVE_JSON_OBJECT_MAPPER =
            JsonMapper.builder().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true).build();

    private static final ObjectMapper SORTED_JSON_OBJECT_MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static ObjectMapper getFailSafeJsonObjectMapper() {
        return FAIL_SAFE_JSON_OBJECT_MAPPER;
    }

    /**
     * Get a default ObjectMapper with no special configuration.
     */
    public static ObjectMapper getJsonObjectMapper() {
        return JSON_OBJECT_MAPPER;
    }

    /**
     * Get an ObjectMapper that fails on unknown properties.
     */
    public static ObjectMapper getStrictJsonObjectMapper() {
        return STRICT_JSON_OBJECT_MAPPER;
    }

    /**
     * Get an ObjectMapper with case-insensitive property matching.
     */
    public static ObjectMapper getCaseInsensitiveJsonObjectMapper() {
        return CASE_INSENSITIVE_JSON_OBJECT_MAPPER;
    }

    /**
     * Get an ObjectMapper that sorts properties alphabetically and map entries by keys.
     */
    public static ObjectMapper getSortedJsonObjectMapper() {
        return SORTED_JSON_OBJECT_MAPPER;
    }

    private SerializerFactory() {
    }
}
