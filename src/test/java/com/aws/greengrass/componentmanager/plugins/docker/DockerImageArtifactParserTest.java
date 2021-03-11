/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageArtifactParserTest {

    @Test
    void GIVEN_docker_image_artifact_uri_WHEN_valid_THEN_succeed() throws Exception {

        assertAll(getImage("docker:www.amazon.com:8080/image:v1.10"),
                "image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com/image:v1.10"),
                "image",
                "v1.10",
                null,
                "www.amazon.com",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:foo/image:v1.10"),
                "image",
                "v1.10",
                null,
                "foo",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:image:v1.10"),
                "image",
                "v1.10",
                null,
                "registry.hub.docker.com/library",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path/to/image:v1.10"),
                "path/to/image", "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path_to/image:v1.10"),
                "path_to/image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path__to/image:v1.10"),
                "path__to/image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path.to/image:v1.10"),
                "path.to/image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path-to/image:v1.10"),
                "path-to/image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path--to/image:v1.10"),
                "path--to/image",
                "v1.10",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path/to/image:v1"),
                "path/to/image",
                "v1",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/path/to/image"),
                "path/to/image",
                "latest",
                null,
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:www.amazon.com:8080/image@sha256:c4ffb87b09eba99383ee89b309d6d521"),
                "image",
                null,
                "sha256:c4ffb87b09eba99383ee89b309d6d521",
                "www.amazon.com:8080",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:registry/image_name@sha256:c4ffb87b09eba99383ee89b309d6d521"),
                "image_name",
                null,
                "sha256:c4ffb87b09eba99383ee89b309d6d521",
                "registry",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:image_name@sha256:c4ffb87b09eba99383ee89b309d6d521"),
                "image_name", null,
                "sha256:c4ffb87b09eba99383ee89b309d6d521",
                "registry.hub.docker.com/library",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

        assertAll(getImage("docker:image_name"),
                "image_name",
                "latest",
                null,
                "registry.hub.docker.com/library",
                Registry.RegistryType.PUBLIC,
                Registry.RegistrySource.OTHER);

    }

    private Image getImage(String uriString) throws InvalidArtifactUriException {
        return DockerImageArtifactParser
                .getImage(ComponentArtifact.builder().artifactUri(URI.create(uriString)).build());
    }

    private void assertAll(Image actual, String name, String tag, String digest, String registryEndpoint,
                           Registry.RegistryType type, Registry.RegistrySource source) {
        assertEquals(name, actual.getName());
        assertEquals(tag, actual.getTag());
        assertEquals(digest, actual.getDigest());
        assertEquals(registryEndpoint, actual.getRegistry().getEndpoint());
        assertEquals(type, actual.getRegistry().getType());
        assertEquals(source, actual.getRegistry().getSource());
    }

    @Test
    void GIVEN_docker_image_artifact_uri_WHEN_invalid_THEN_fail() {
        String validHex = "c4ffb87b09eba99383ee89b309d6d521";

        // missing digest algorithm
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/image@" + validHex));

        // invalid digest algorithm
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/image@256sha:" + validHex));

        // too short digest hex
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/image@sha256:1234"));

        // missing digest hex
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/image@sha256:"));

        // digest hex contains invalid char
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/image@sha256:" + validHex + "hijk"));

        // unrecognized char
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path/to/image:v1#10"));

        // starting with non alphanumeric
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path/to/image:-v1"));

        // missing tag or digest
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path/to/image:"));

        // path component is ended with non alphanumeric char
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path_/image:v1.10"));

        // path component is started with non alphanumeric char
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/_path/image:v1.10"));

        // path component has invalid char #
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path#to/image:v1.10"));

        // path component has invalid char ..
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:8080/path..to/image:v1.10"));

        // domain port is missing
        assertThrows(InvalidArtifactUriException.class, () -> getImage("docker:www.amazon.com:/image:v1.10"));

        // domain url is missing
        assertThrows(InvalidArtifactUriException.class, () -> getImage("docker::1234/image:v1.10"));

        // domain port is not numeric
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.amazon.com:port/image:v1.10"));

        // domain component starting with -
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.-amazon-.com:8080/image:v1.10"));

        // domain component has invalid char $
        assertThrows(InvalidArtifactUriException.class,
                () -> getImage("docker:www.$amazon.com:8080/image:v1.10"));
    }

}
