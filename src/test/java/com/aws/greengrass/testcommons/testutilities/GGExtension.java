/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

@SuppressWarnings({
        "PMD.CollapsibleIfStatements"
})
public class GGExtension
        implements
            BeforeEachCallback,
            AfterEachCallback,
            BeforeAllCallback,
            AfterAllCallback,
            ParameterResolver {

    private static final ExceptionLogProtector logProt = new ExceptionLogProtector();
    private static final SpawnedProcessProtector processProt = new SpawnedProcessProtector();
    private static final ThreadProtector threadProt = new ThreadProtector();

    private static final Object[] implementors = {
            logProt, processProt, threadProt
    };

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        for (Object o : implementors) {
            if (o instanceof AfterAllCallback) {
                ((AfterAllCallback) o).afterAll(context);
            }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        for (Object o : implementors) {
            if (o instanceof AfterEachCallback) {
                ((AfterEachCallback) o).afterEach(context);
            }
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        for (Object o : implementors) {
            if (o instanceof BeforeAllCallback) {
                ((BeforeAllCallback) o).beforeAll(context);
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        for (Object o : implementors) {
            if (o instanceof BeforeEachCallback) {
                ((BeforeEachCallback) o).beforeEach(context);
            }
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        for (Object o : implementors) {
            if (o instanceof ParameterResolver) {
                if (((ParameterResolver) o).supportsParameter(parameterContext, extensionContext)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        for (Object o : implementors) {
            if (o instanceof ParameterResolver) {
                if (((ParameterResolver) o).supportsParameter(parameterContext, extensionContext)) {
                    return ((ParameterResolver) o).resolveParameter(parameterContext, extensionContext);
                }
            }
        }
        return null;
    }
}
