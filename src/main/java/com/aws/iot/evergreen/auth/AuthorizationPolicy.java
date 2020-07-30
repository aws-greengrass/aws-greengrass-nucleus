/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class AuthorizationPolicy {
    @NonNull String policyId;
    String policyDescription;
    @NonNull Set<String> principals;
    @NonNull Set<String> operations;
    Set<String> resources;

    enum PolicyComponentTypes {
        POLICYDESCRIPTION("policyDescription"),
        PRINCIPALS("principals"),
        OPERATIONS("operations"),
        RESOURCES("resources");

        private final String name;

        PolicyComponentTypes(String s) {
            name = s;
        }

        public boolean equalsName(String otherName) {
            return name.equals(otherName);
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}
