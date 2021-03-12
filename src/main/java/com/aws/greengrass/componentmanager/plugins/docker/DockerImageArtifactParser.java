/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides utilities to interpret and validate a docker image artifact URI.
 *
 */
public final class DockerImageArtifactParser {
    public static final String INVALID_DOCKER_ARTIFACT_URI_MESSAGE = "Invalid Docker image artifact uri. "
            + "URI should follow the format of `docker:registry/image_name[:tag]|[@digest]`, "
            + "where registry, tag or digest are optional";
    private static final Logger logger = LogManager.getLogger(DockerImageArtifactParser.class);
    // Artifact URI follows docker's official grammar for registry-image spec
    // Reference - https://github.com/distribution/distribution/blob/main/reference/reference.go#L6
    // Example docker uri - docker:foo.bar:8080/path/to/image:v1.10
    // Example domain - www.amazon-us.com:8080
    private static final String DOMAIN_COMPONENT_REGEX = "([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])";
    private static final String DOMAIN_REGEX =
            String.format("(%s(\\.%s)*(:[0-9]+)?)", DOMAIN_COMPONENT_REGEX, DOMAIN_COMPONENT_REGEX);

    private static final String PATH_COMPONENT_REGEX = "([a-z0-9]+)(([_.]|__|[-]*)([a-z0-9]+))*";
    private static final String PATH_REGEX = String.format("(%s(/%s)*)", PATH_COMPONENT_REGEX, PATH_COMPONENT_REGEX);

    private static final String DOCKER_ARTIFACT_REGEX_STRING = String.format(
            "^docker:((?<registry>%s)/)?(?<imageName>%s)(?<imageVersion>(:(?<imageTag>[\\w.-]+)|"
                    + "@(?<imageDigest>[\\w:.-]+)))?$", DOMAIN_REGEX, PATH_REGEX);
    public static final Pattern DOCKER_ARTIFACT_REGEX_PATTERN = Pattern.compile(DOCKER_ARTIFACT_REGEX_STRING);

    // More detailed regex for tag and digest
    // Example tag - v1.12.1
    private static final String IMAGE_TAG_REGEX = "([\\w][\\w.-]{0,127})";
    private static final Pattern IMAGE_TAG_REGEX_PATTERN = Pattern.compile("^" + IMAGE_TAG_REGEX + "$");

    // <algorithm+hex> ; example digest - @sha256:c4ffb87b09eba99383ee89b309d6d521
    private static final String DIGEST_REGEX = "([A-Za-z][A-Za-z0-9]*:[0-9a-fA-F]{32,})";
    private static final Pattern DIGEST_REGEX_PATTERN = Pattern.compile("^" + DIGEST_REGEX + "$");

    private static List<String> PRIVATE_ECR_REGISTRY_IDENTIFIERS = Arrays.asList("dkr.ecr", "amazonaws");
    private static String PUBLIC_ECR_REGISTRY_IDENTIFIER = "public.ecr.aws";

    private DockerImageArtifactParser() {
    }

    /**
     * Extract and image instance with image and registry details from an artifact URI, also validates the URI.
     *
     * @param artifact component artifact
     * @return Image instance with image and registry details
     * @throws InvalidArtifactUriException If URI validation against docker defined specification fails
     */
    public static Image getImage(ComponentArtifact artifact) throws InvalidArtifactUriException {
        // Valid docker uri is docker:registry/image_name:tag|@digest
        Matcher matcher = DOCKER_ARTIFACT_REGEX_PATTERN.matcher(artifact.getArtifactUri().toString());
        if (!matcher.find()) {
            throw new InvalidArtifactUriException(INVALID_DOCKER_ARTIFACT_URI_MESSAGE);
        }

        String registryEndpoint = matcher.group("registry");
        if (Utils.isEmpty(registryEndpoint)) {
            // No registry specified, the default is docker hub's registry server
            // e.g. ubuntu == library/ubuntu == docker.io/library/ubuntu == registry.hub.docker.com/library/ubuntu
            registryEndpoint = "registry.hub.docker.com/library";
        }

        String imageName = matcher.group("imageName");
        if (Utils.isEmpty(imageName)) {
            throw new InvalidArtifactUriException(INVALID_DOCKER_ARTIFACT_URI_MESSAGE);
        }

        String imageTag = matcher.group("imageTag");
        if (!Utils.isEmpty(imageTag)) {
            Matcher tagMatcher = IMAGE_TAG_REGEX_PATTERN.matcher(imageTag);
            if (!tagMatcher.find()) {
                throw new InvalidArtifactUriException(
                        String.format("Image tag %s has invalid format, should follow regex " + IMAGE_TAG_REGEX,
                                imageTag));
            }
        }

        String imageDigest = matcher.group("imageDigest");
        if (!Utils.isEmpty(imageDigest)) {
            Matcher digestMatcher = DIGEST_REGEX_PATTERN.matcher(imageDigest);
            if (!digestMatcher.find()) {
                throw new InvalidArtifactUriException(
                        String.format("Image digest %s has invalid format, should follow regex " + DIGEST_REGEX,
                                imageDigest));
            }
        }

        String imageVersion = matcher.group("imageVersion");
        if (Utils.isEmpty(imageVersion)) {
            // No digest/tag specified, docker engine will pull the latest image
            logger.atWarn().kv("artifact-uri", artifact.getArtifactUri())
                    .log("An image version is not present. Specify an image version via an image tag or digest to"
                            + " ensure that the component is immutable and that the deployment will consistently "
                            + "deliver the same artifacts");
            imageTag = "latest";
        }

        return new Image(getRegistryFromArtifact(registryEndpoint), imageName, imageTag, imageDigest,
                artifact.getArtifactUri());
    }

    private static Registry getRegistryFromArtifact(String endpoint) {
        Registry.RegistryType type;
        Registry.RegistrySource source;

        if (endpoint.contains(PUBLIC_ECR_REGISTRY_IDENTIFIER)) {
            source = Registry.RegistrySource.ECR;
            type = Registry.RegistryType.PUBLIC;
        } else if (containsAll(endpoint, PRIVATE_ECR_REGISTRY_IDENTIFIERS)) {
            source = Registry.RegistrySource.ECR;
            type = Registry.RegistryType.PRIVATE;
        } else {
            source = Registry.RegistrySource.OTHER;
            // Currently all registries other than ECR are assumed to be public since
            // we do not have support for private registries other than ECR.
            // When we do support private non-ECR registries, this will need to be
            // inferred based on additional component artifact attributes
            type = Registry.RegistryType.PUBLIC;
        }
        return new Registry(endpoint, source, type);
    }


    private static boolean containsAll(String str, List<String> subStrs) {
        for (String subStr : subStrs) {
            if (!str.contains(subStr)) {
                return false;
            }
        }
        return true;
    }
}
