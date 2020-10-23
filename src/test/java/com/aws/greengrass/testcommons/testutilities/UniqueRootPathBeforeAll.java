/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import static com.aws.greengrass.testcommons.testutilities.UniqueRootStoreHelper.KEY;

public class UniqueRootPathBeforeAll implements BeforeAllCallback {
    private static final Namespace NAMESPACE = Namespace.create(UniqueRootPathBeforeAll.class);

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(KEY, UniqueRootStoreHelper::createPath,
                ExtensionContext.Store.CloseableResource.class);
    }
}
