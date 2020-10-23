/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import static com.aws.greengrass.testcommons.testutilities.UniqueRootStoreHelper.KEY;

public class UniqueRootPathBeforeEach implements BeforeEachCallback {
    private static final Namespace NAMESPACE = Namespace.create(UniqueRootPathBeforeEach.class);

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(KEY, UniqueRootStoreHelper::createPath,
                ExtensionContext.Store.CloseableResource.class);
    }
}
