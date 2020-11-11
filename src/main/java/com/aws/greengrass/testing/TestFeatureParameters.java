/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Some functionality is enabled only for integration testing. Such functionality is subject to change between
 * releases of the Greengrass Nucleus and/or may result in unstable behavior in production and should be avoided.
 */
public final class TestFeatureParameters {
    private static final Logger LOGGER = LogManager.getLogger(TestFeatureParameters.class);
    private static final Map<String, Consumer<Boolean>> handlerRegistrationCallbacks = new ConcurrentHashMap<>();

    /**
     * Default implementation when not overridden.
     */
    public static final TestFeatureParameterInterface DEFAULT_HANDLER = new TestFeatureParameterInterface() {

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T retrieveWithDefault(Class<? extends T> cls, String featureParameterName, T defaultValue) {
            // Runtime validation of cls not required for default value
            return defaultValue;
        }
    };

    private static final AtomicReference<TestFeatureParameterInterface> handler =
                                                                            new AtomicReference<>(DEFAULT_HANDLER);

    private TestFeatureParameters() {
        // No instance methods
    }

    /**
     * Retrieve either the provided default (production) value of a parameter, or, under test conditions, an
     * alternative value specific for the test being undertaken.
     *
     * @param cls Expected type to handle runtime validation of override type
     * @param featureParameterName Name of parameter to query.
     * @param defaultValue Value to use when not overridden under test conditions.
     * @param <T> Simple parameter type (String, Integer, etc).
     * @return Default (production) value, or override (testing) value.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // intentional reference equals
    public static <T> T retrieveWithDefault(Class<? extends T> cls, String featureParameterName, T defaultValue) {
        TestFeatureParameterInterface actualHandler = handler.get();
        T value = actualHandler.retrieveWithDefault(cls, featureParameterName, defaultValue);
        if (defaultValue == value) {
            // Pass through default value logged at debug level
            LOGGER.atDebug().addKeyValue("FeatureParameterName", featureParameterName)
                    .addKeyValue("DefaultValue", defaultValue)
                    .log("Default Feature Parameter \"{}\"=\"{}\" via {}", featureParameterName, value,
                            actualHandler.getClass().getName());
        } else {
            // Override occurred, this is intentionally noisy
            LOGGER.atWarn().addKeyValue("FeatureParameterName", featureParameterName)
                    .addKeyValue("ProductionValue", defaultValue)
                    .addKeyValue("OverrideValue", value)
                    .log("Override Feature Parameter \"{}\"=\"{}\" via {}", featureParameterName, value,
                            actualHandler.getClass().getName());
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
    public static TestFeatureParameterInterface internalEnableTestingFeatureParameters(
            TestFeatureParameterInterface newHandler) {
        if (newHandler == DEFAULT_HANDLER) {
            LOGGER.info("Testing Feature Parameters has been disabled.");
        } else {
            LOGGER.warn("Testing Feature Parameters has been enabled. This operation is not supported in "
                    + "a production environment.");
        }
        TestFeatureParameterInterface olderHandler = TestFeatureParameters.handler.getAndSet(newHandler);
        handlerRegistrationCallbacks.values().forEach(callback -> callback.accept(newHandler != DEFAULT_HANDLER));
        return olderHandler;
    }

    /**
     * Disable testing feature parameters.
     *
     * @return previous handler
     */
    public static TestFeatureParameterInterface internalDisableTestingFeatureParameters() {
        return internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
    }

    /**
     * Register a callback to notify when the handler is set.
     * @param serviceName   Name of the service requesting a callback.
     * @param callback      The callback function.
     */
    public static void registerHandlerCallback(String serviceName, Consumer<Boolean> callback) {
        handlerRegistrationCallbacks.put(serviceName, callback);
    }

    /**
     * Unregister a service from getting handler set notification.
     * @param serviceName   Name of the service unregistering.
     */
    public static void unRegisterHandlerCallback(String serviceName) {
        handlerRegistrationCallbacks.remove(serviceName);
    }
}
