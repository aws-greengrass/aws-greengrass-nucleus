/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
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
    public static final Logger logger = LogManager.getLogger(Image.class);

    private Registry registry;
    private String name;
    private String tag;
    private String digest;
    @EqualsAndHashCode.Include
    private URI artifactUri;


    /**
     * Build an instance from a component artifact.
     *
     * @param uri Artifact uri
     * @return Image instance
     */
    public static Image fromArtifactUri(URI uri) {
        // Format - <docker:registry/host:port/image:tag|@digest>, everything except for the image name is optional
        // TODO : Add more validation/use regex per docker's official grammar - https://github
        //  .com/distribution/distribution/blob/main/reference/reference.go#L6
        String rawId = uri.getSchemeSpecificPart();
        String digest = null;
        String tag = null;
        String endpointAndImage;

        // Look for digest first
        Pair<String, String> digestParts = splitBySeparator(rawId, '@', true, null);
        if (Utils.isNotEmpty(digestParts.getRight())) {
            endpointAndImage = digestParts.getLeft();
            digest = digestParts.getRight();
        } else {
            // If no digest specified, look for tag
            Pair<String, String> tagParts = splitBySeparator(rawId, ':', true, null);
            if (Utils.isNotEmpty(tagParts.getRight())) {
                endpointAndImage = tagParts.getLeft();
                tag = tagParts.getRight();
            } else {
                // No digest/tag specified, docker engine will pull the latest image
                logger.atWarn().kv("artifact-uri", uri).log("It appears that you are not using a specific version of "
                        + "the image with your component, for ensuring component immutability, it is advised that you "
                        + "use a specific image version in your component version using image tag/digest");
                tag = "latest";
                endpointAndImage = rawId;
            }
        }
        // No registry specified, the default is docker hub's registry server
        // e.g. ubuntu == library/ubuntu == docker.io/library/ubuntu == registry.hub.docker.com/library/ubuntu
        Pair<String, String> endpointAndImageParsed =
                splitBySeparator(endpointAndImage, '/', false, "registry.hub.docker.com/library");

        return new Image(new Registry(endpointAndImageParsed.getLeft()), endpointAndImageParsed.getRight(), tag, digest,
                uri);
    }

    /**
     * Split a string, docker registry+image spec within this class, by given separator that identifies unique parts of
     * the specified string.
     *
     * @param toSplit                       String to split
     * @param separator                     Separator to split by
     * @param setAllLeftWhenSeparatorAbsent When provided separator is not present in source string, state if the whole
     *                                      source string should be set as the first return value. If false, the
     *                                      opposite will happen, i.e. it will be set as the second return value
     *                                      instead.
     * @param defaultWhenSeparatorAbsent    When provided separator is not present in source string, use this
     *                                      as default value for the missing part
     * @return Pair of first and second value as split result
     */
    private static Pair<String, String> splitBySeparator(String toSplit, char separator,
                                                         boolean setAllLeftWhenSeparatorAbsent,
                                                         String defaultWhenSeparatorAbsent) {
        String left;
        String right;

        if (toSplit.indexOf(separator) > -1) {
            int separatorIndex = toSplit.lastIndexOf(separator);
            left = toSplit.substring(0, separatorIndex);
            right = toSplit.substring(separatorIndex + 1);
        } else if (setAllLeftWhenSeparatorAbsent) {
            left = toSplit;
            right = defaultWhenSeparatorAbsent;
        } else {
            left = defaultWhenSeparatorAbsent;
            right = toSplit;
        }

        return new Pair<>(left, right);
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
