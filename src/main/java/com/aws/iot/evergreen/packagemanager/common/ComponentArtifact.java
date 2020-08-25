/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.net.URI;

@JsonDeserialize(builder = ComponentArtifact.ComponentArtifactBuilder.class)
@Value
@Builder
public class ComponentArtifact {
    URI uri;

    String digest;

    String algorithm;

    String unarchive;

    @JsonPOJOBuilder(withPrefix = "")
    public static class ComponentArtifactBuilder {
    }
}
