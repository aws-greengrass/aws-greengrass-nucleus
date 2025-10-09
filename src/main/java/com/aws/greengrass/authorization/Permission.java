/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Builder
@Value
public class Permission {
    @NonNull
    String principal;
    @NonNull
    String operation;
    String resource;
}
