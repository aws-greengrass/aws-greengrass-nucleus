/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.packagemanager.exceptions.InvalidArtifactUriException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public interface ArtifactDownloader {
    File downloadToPath(PackageIdentifier packageIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException, InvalidArtifactUriException;
}
