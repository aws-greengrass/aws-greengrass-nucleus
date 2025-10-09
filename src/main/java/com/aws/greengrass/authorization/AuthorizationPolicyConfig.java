/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.Set;

/**
 * Class which represents the innermost access control config.
 */
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthorizationPolicyConfig {
    @NonNull
    String policyDescription;
    @NonNull
    Set<String> operations;
    @NonNull
    Set<String> resources;
}
