/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AuthZPolicy {
    @NonNull String policyId;
    String policyDescription;
    @NonNull List<String> sources;
    @NonNull List<String> operations;
    List<String> resources;
}
