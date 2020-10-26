/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.testing;

/**
 * Interface to allow alternative implementation of {@link TestFeatureParameters#get(String, Object)}.
 */
/*PackagePrivate*/
interface TestFeatureParameterInterface {
    /**
     * Retrieve either the provided production value of a parameter, or, under test conditions, an alternative value
     * specific for the test being undertaken.
     *
     * @param featureParameterName Name of parameter to query.
     * @param productionValue Value to use when not overridden under test conditions.
     * @param <T> Simple parameter type (String, Integer, etc).
     * @return Production value, or override value.
     */
    <T> T get(String featureParameterName, T productionValue);
}
