/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

@Builder
@Value
@EqualsAndHashCode
public class Permission {
    @NonNull String principal;
    @NonNull String operation;
    String resource;
}
