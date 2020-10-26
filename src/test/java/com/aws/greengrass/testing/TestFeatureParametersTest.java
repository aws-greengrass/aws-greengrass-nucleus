/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class TestFeatureParametersTest {

    @AfterEach
    public void disableFeatureParametersAfter() {
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @Test
    public void GIVEN_feature_flag_retrieved_WHEN_not_enabled_THEN_use_provided_value() {
        String providedValue = "ThisValueWasProvided";
        String featureFlag = "SomeFeatureFlagThatDoesNotGetValidated";

        // by default, value is pass-through
        assertThat(TestFeatureParameters.get(featureFlag, providedValue), is(sameInstance(providedValue)));
    }

    @Test
    public void GIVEN_feature_flag_retrieved_WHEN_enabled_THEN_use_override_value() {
        String featureFlag = "SomeFeatureFlagThatWouldBeHandled";
        Integer someInputValue = 1234;
        Integer specificReturnValue = 5678; // checked by reference

        TestFeatureParameterInterface handler = mock(TestFeatureParameterInterface.class);
        when(handler.get(featureFlag, someInputValue)).thenReturn(specificReturnValue);
        TestFeatureParameters.internalEnableTestingFeatureParameters(handler);

        // by default, input value is accepted
        assertThat(TestFeatureParameters.get(featureFlag, someInputValue), is(sameInstance(specificReturnValue)));

        // make sure
        TestFeatureParameters.internalDisableTestingFeatureParameters();
        assertThat(TestFeatureParameters.get(featureFlag, someInputValue), is(sameInstance(someInputValue)));

    }

    @Test
    public void GIVEN_feature_flag_retrieved_WHEN_disabled_THEN_use_provided_value() {
        String featureFlag = "SomeFeatureFlagThatWouldBeHandled";
        Integer someInputValue = 1234;
        Integer specificReturnValue = 5678; // checked by reference

        TestFeatureParameterInterface handler = mock(TestFeatureParameterInterface.class);
        when(handler.get(featureFlag, someInputValue)).thenReturn(specificReturnValue);
        // Enable (and verify)
                TestFeatureParameters.internalEnableTestingFeatureParameters(handler);
        assertThat(TestFeatureParameters.get(featureFlag, someInputValue), is(sameInstance(specificReturnValue)));
        // Now verify disable, which is primary test
        TestFeatureParameterInterface priorHandler =
                TestFeatureParameters.internalDisableTestingFeatureParameters();
        assertThat(TestFeatureParameters.get(featureFlag, someInputValue), is(sameInstance(someInputValue)));
        assertThat(priorHandler, is(sameInstance(handler)));
    }
}
