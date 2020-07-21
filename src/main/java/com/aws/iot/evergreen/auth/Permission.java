/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Builder
@Value
public class Permission {
    @NonNull String source;
    @NonNull String operation;
    String resource;
}
