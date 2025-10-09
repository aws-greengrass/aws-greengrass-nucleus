/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.net.URI;

/**
 * Represents a docker image derived from a component artifact specification.
 */
@ToString
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Image {
    private static final Logger logger = LogManager.getLogger(Image.class);

    private Registry registry;
    private String name;
    private String tag;
    private String digest;
    @EqualsAndHashCode.Include
    private URI artifactUri;

    /**
     * Build an instance from a component artifact.
     *
     * @param artifact Component artifact for the image
     * @return Image instance
     * @throws InvalidArtifactUriException when the URI is malformed
     */
    public static Image fromArtifactUri(ComponentArtifact artifact) throws InvalidArtifactUriException {
        // Validate and construct image object for the component artifact
        return DockerImageArtifactParser.getImage(artifact);
    }

    /**
     * Derive the full name of the image in docker-defined format that can be used with the docker engine.
     *
     * @return full docker image name in the docker-specific format
     */
    public String getImageFullName() {
        return this.artifactUri.getSchemeSpecificPart();
    }

}
