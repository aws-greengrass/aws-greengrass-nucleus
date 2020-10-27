/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


@ExtendWith(GGExtension.class)
class PlatformResolverTest {

    @Test
    void testCurrentPlatform() throws Exception {
        // TODO: move to UAT
        System.out.println(PlatformResolver.CURRENT_PLATFORM);
    }

}
