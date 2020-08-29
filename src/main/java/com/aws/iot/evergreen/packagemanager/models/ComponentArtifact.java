/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

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

    String unarchive; //TODO make it enum
}
