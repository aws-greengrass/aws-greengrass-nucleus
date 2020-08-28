/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SerializerFactory {
    private static final ObjectMapper RECIPE_SERIALIZER =
            new ObjectMapper(new YAMLFactory()).enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                                               .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                                               .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static ObjectMapper getRecipeSerializer() {
        return RECIPE_SERIALIZER;
    }


}
