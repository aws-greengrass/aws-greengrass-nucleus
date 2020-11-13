/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing;

/**
 * Interface to allow alternative implementation of
 * {@link TestFeatureParameters#retrieveWithDefault(Class, String, Object)}.
 */
public interface TestFeatureParameterInterface {
    /**
     * Retrieve either the provided production value of a parameter, or, under test conditions, an alternative value
     * specific for the test being undertaken.
     *
     * @param cls Expected type to handle runtime validation of override type
     * @param featureParameterName Name of parameter to query.
     * @param productionValue Value to use when not overridden under test conditions.
     * @param <T> Simple parameter type (String, Integer, etc).
     * @return Production value, or override value.
     */
    <T> T retrieveWithDefault(Class<? extends T> cls, String featureParameterName, T productionValue);
}
