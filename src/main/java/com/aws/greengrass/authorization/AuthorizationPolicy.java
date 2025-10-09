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
 * Class that holds full access control policy which translates to {@link Permission}.
 */
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthorizationPolicy implements Comparable<AuthorizationPolicy> {
    @NonNull
    String policyId;
    String policyDescription;
    @NonNull
    Set<String> principals;
    @NonNull
    Set<String> operations;
    Set<String> resources;

    @Override
    public int compareTo(AuthorizationPolicy other) {
        return this.policyId.compareTo(other.policyId);
    }
}
