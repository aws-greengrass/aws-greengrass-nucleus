/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.platforms.UserPlatform;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WindowsGroupAttributes implements UserPlatform.BasicAttributes {
    String principalName;
    String principalIdentifier;
}
