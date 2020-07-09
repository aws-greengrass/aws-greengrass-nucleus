/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.net.URI;

@Getter
public class ComponentArtifact {
    private URI artifactUri;
    private String checksum;
    private String algorithm;

    /**
     * Constructor.
     *
     * @param artifactUri artifactUri
     * @param checksum checksum
     */
    @JsonCreator
    public ComponentArtifact(@JsonProperty("Uri") URI artifactUri,
                             @JsonProperty("Checksum") String checksum,
                             @JsonProperty("Algorithm") String algorithm) {
        this.artifactUri = artifactUri;
        this.checksum = checksum;
        this.algorithm = algorithm;
    }
}
