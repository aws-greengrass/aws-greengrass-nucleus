/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.testing;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Some functionality is enabled only for integration testing. Such functionality is subject to change between
 * releases of the Greengrass Nucleus and/or may result in unstable behavior in production and should be avoided.
 */
public final class TestFeatureParameters {
    private static final Logger LOGGER = LogManager.getLogger(TestFeatureParameters.class);

    /**
     * Default implementation when not overridden.
     */
    /*PackagePrivate*/ static TestFeatureParameterInterface DEFAULT_HANDLER = new TestFeatureParameterInterface() {
        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T get(String featureParameterName, T productionValue) {
            return productionValue;
        }
    };

    private static final AtomicReference<TestFeatureParameterInterface> handler =
                                                                            new AtomicReference<>(DEFAULT_HANDLER);

    private TestFeatureParameters() {
        // No instance methods
    }

    /**
     * Retrieve either the provided production value of a parameter, or, under test conditions, an alternative value
     * specific for the test being undertaken.
     *
     * @param featureParameterName Name of parameter to query.
     * @param productionValue Value to use when not overridden under test conditions.
     * @param <T> Simple parameter type (String, Integer, etc).
     * @return Production value, or override value.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // intentional reference equals
    public static <T> T get(String featureParameterName, T productionValue) {
        T value = handler.get().get(featureParameterName, productionValue);
        if (productionValue == value && LOGGER.isDebugEnabled()) {
            // Pass through production value logged at debug level
            LOGGER.atDebug().addKeyValue("FeatureParameterName", featureParameterName)
                    .addKeyValue("ProductionValue", productionValue)
                    .log("Production Feature Parameter \"{}\"=\"{}\" via {}", featureParameterName, value,
                            handler.get().getClass().getSimpleName());
        } else if (productionValue != value && LOGGER.isTraceEnabled()) {
            // Override occurred, this is intentionally noisy
            LOGGER.atWarn().addKeyValue("FeatureParameterName", featureParameterName)
                    .addKeyValue("ProductionValue", productionValue)
                    .addKeyValue("OverrideValue", value)
                    .log("Override Feature Parameter \"{}\"=\"{}\" via {}", featureParameterName, value,
                            handler.get().getClass().getSimpleName());
        }
        return value;
    }

    /**
     * Called under test conditions to provide a feature parameter handler. Note that this method is subject to change
     * under future releases of Nucleus.
     *
     * @param newHandler New handler to use
     * @return previous handler
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // intentional reference equals
    /*PackagePrivate*/ static TestFeatureParameterInterface internalEnableTestingFeatureParameters(
            TestFeatureParameterInterface newHandler) {
        if (newHandler == DEFAULT_HANDLER) {
            LOGGER.info("Testing Feature Parameters has been disabled.");
        } else {
            LOGGER.warn("Testing Feature Parameters has been enabled. This operation is not supported in "
                    + "a production environment.");
        }
        return TestFeatureParameters.handler.getAndSet(newHandler);
    }

    /**
     * Disable testing feature parameters.
     *
     * @return previous handler
     */
    /*PackagePrivate*/ static TestFeatureParameterInterface internalDisableTestingFeatureParameters() {
        return internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
    }

}
