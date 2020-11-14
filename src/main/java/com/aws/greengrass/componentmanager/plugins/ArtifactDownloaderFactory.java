/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.S3SdkClientFactory;

import java.net.URI;
import java.nio.file.Path;
import javax.inject.Inject;

public class ArtifactDownloaderFactory {
    private static final String GREENGRASS_SCHEME = "GREENGRASS";
    private static final String S3_SCHEME = "S3";

    private final S3SdkClientFactory s3ClientFactory;

    private final GreengrassComponentServiceClientFactory greengrassComponentServiceClientFactory;

    /**
     * ArtifactDownloaderFactory constructor.
     * @param s3SdkClientFactory                        s3SdkClientFactory
     * @param greengrassComponentServiceClientFactory   greengrassComponentServiceClientFactory
     */
    @Inject
    public ArtifactDownloaderFactory(S3SdkClientFactory s3SdkClientFactory,
                              GreengrassComponentServiceClientFactory greengrassComponentServiceClientFactory) {
        this.s3ClientFactory = s3SdkClientFactory;
        this.greengrassComponentServiceClientFactory = greengrassComponentServiceClientFactory;
    }

    /**
     * Return the artifact downloader instance.
     * @param identifier componentIdentifier
     * @param artifact componentArtifact
     * @param artifactDir directory to download artifact to
     * @return Artifact downloader
     * @throws PackageLoadingException throw if URI scheme not supported
     * @throws InvalidArtifactUriException throw if s3 url not valid
     */
    public ArtifactDownloader getArtifactDownloader(ComponentIdentifier identifier, ComponentArtifact artifact,
                                                    Path artifactDir)
            throws PackageLoadingException, InvalidArtifactUriException {
        URI artifactUri = artifact.getArtifactUri();
        String scheme = artifactUri.getScheme() == null ? null : artifactUri.getScheme().toUpperCase();
        if (GREENGRASS_SCHEME.equals(scheme)) {
            return new GreengrassRepositoryDownloader(
                    greengrassComponentServiceClientFactory, identifier, artifact, artifactDir);
        }
        if (S3_SCHEME.equals(scheme)) {
            return new S3Downloader(s3ClientFactory, identifier, artifact, artifactDir);
        }
        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme));
    }
}
