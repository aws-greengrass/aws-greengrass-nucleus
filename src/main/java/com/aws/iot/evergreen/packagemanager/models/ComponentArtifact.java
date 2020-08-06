/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.net.URI;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ComponentArtifact {

    @JsonProperty("Uri")
    private URI artifactUri;

    @JsonProperty("Checksum")
    private String checksum;

    @JsonProperty("Algorithm")
    private String algorithm;
    
    @Override
    public String toString() {
        return artifactUri.toString();
    }
}
