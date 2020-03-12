/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import lombok.AllArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
public class DependencyResolver {
    // TODO: temporarily suppress this warning which will be gone after these fields get used.
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private final PackageRegistry packageRegistry;

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     *
     * @param document deployment document
     * @return a map of packages to be run on the device to version constraints
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     * @throws InterruptedException            when the running thread is interrupted
     */
    public Map<PackageIdentifier, String> resolveDependencies(DeploymentDocument document)
            throws PackageVersionConflictException, InterruptedException {
        return new HashMap<>();
    }
}
