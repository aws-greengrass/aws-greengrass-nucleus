/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.lifecyclemanager.RunWithPathOwnershipHandler;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;

/**
 * Integration tests running as non super user cannot change artifacts. This will skip the ownership update so tests
 * will pass if user is not a super user.
 */
public class NoOpPathOwnershipHandler extends RunWithPathOwnershipHandler {

    public static void register(Kernel kernel) {
        kernel.getContext()
                .put(RunWithPathOwnershipHandler.class, new NoOpPathOwnershipHandler(kernel.getNucleusPaths()));
    }

    public NoOpPathOwnershipHandler(NucleusPaths paths) {
        super(paths);
    }

    @Override
    public void updateOwner(ComponentIdentifier id, RunWith runWith) throws IOException {
        if (Platform.getInstance().lookupCurrentUser().isSuperUser()) {
            super.updateOwner(id, runWith);
        }
        // do nothing
    }
}
