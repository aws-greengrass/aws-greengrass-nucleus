/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.iot.evergreen.deployment.converter;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class DeploymentDocumentConverter {

    private static final String ANY_VERSION = "*";

    public static DeploymentDocument convertFromLocalOverrideRequestAndRoot(LocalOverrideRequest localOverrideRequest,
                                                                            Map<String, String> rootComponents)  {

         // copy over existing root components
         Map<String, String> newRootComponents = new HashMap<>(rootComponents);

         // remove
        List<String> componentsToRemove = localOverrideRequest.getComponentsToRemove();
        if (componentsToRemove != null && !componentsToRemove.isEmpty()) {
            componentsToRemove.forEach((newRootComponents::remove));
        }

        // add or update
        Map<String, String> componentsToMerge = localOverrideRequest.getComponentsToMerge();
        if (componentsToMerge != null && !componentsToMerge.isEmpty()) {
            componentsToMerge.forEach(newRootComponents::put);
        }

        List<String> rootPackages = new ArrayList<>(newRootComponents.keySet());

        // convert Deployment Config from getComponentNameToConfig, which doesn't include root components necessarily
        List<DeploymentPackageConfiguration> packageConfigurations =
                localOverrideRequest.getComponentNameToConfig().entrySet().stream()
                        .map(entry -> new DeploymentPackageConfiguration(entry.getKey(), ANY_VERSION, entry.getValue()))
                        .collect(Collectors.toList());

        // apply root
         newRootComponents.forEach((rootComponentName, version) -> {
            Optional<DeploymentPackageConfiguration> optionalConfiguration = packageConfigurations.stream()
                    .filter(packageConfiguration -> packageConfiguration.getPackageName().equals(rootComponentName))
                    .findAny();

            if (optionalConfiguration.isPresent()) {
                // if found, set the target version
                optionalConfiguration.get().setResolvedVersion(version);
            } else {
                // if not found, create it
                packageConfigurations.add(new DeploymentPackageConfiguration(rootComponentName, version, null));
            }
        });


        return DeploymentDocument.builder().timestamp(localOverrideRequest.getRequestTimestamp()).deploymentId(localOverrideRequest.getRequestId())
                .rootPackages(rootPackages).deploymentPackageConfigurationList(packageConfigurations).build();
    }

}
