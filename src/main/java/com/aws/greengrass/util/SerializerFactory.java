/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SerializerFactory {

    // most of our use cases need to be fail safe on unknowns, rather than throwing exceptions
    private static final ObjectMapper FAIL_SAFE_JSON_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static ObjectMapper getFailSafeJsonObjectMapper() {
        return FAIL_SAFE_JSON_OBJECT_MAPPER;
    }

    private SerializerFactory() {
    }
}
