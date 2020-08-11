/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class AuthorizationPolicy implements Comparable<AuthorizationPolicy> {
    @NonNull String policyId;
    String policyDescription;
    @NonNull Set<String> principals;
    @NonNull Set<String> operations;
    Set<String> resources;

    enum PolicyComponentTypes {
        POLICY_DESCRIPTION("policyDescription"),
        PRINCIPALS("principals"),
        OPERATIONS("operations"),
        RESOURCES("resources"),
        UNKNOWN("unknown");

        private final String name;

        PolicyComponentTypes(String s) {
            name = s;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    @Override
    public int compareTo(AuthorizationPolicy other) {
        return this.policyId.compareTo(other.policyId);
    }
}
