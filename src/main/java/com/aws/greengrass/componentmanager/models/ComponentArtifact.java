/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.net.URI;

@Value
@Builder
@AllArgsConstructor
public class ComponentArtifact {

    @NonNull URI artifactUri;

    String checksum;

    String algorithm;

    @Builder.Default
    Unarchive unarchive = Unarchive.NONE;

    @Builder.Default
    Permission permission = Permission.builder().build();
}
