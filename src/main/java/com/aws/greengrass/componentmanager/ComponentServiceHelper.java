/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.model.ComponentCandidate;
import com.amazonaws.services.evergreen.model.ComponentPlatform;
import com.amazonaws.services.evergreen.model.GetComponentVersionDeprecatedRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionDeprecatedResult;
import com.amazonaws.services.evergreen.model.RecipeFormatType;
import com.amazonaws.services.evergreen.model.ResolveComponentCandidatesRequest;
import com.amazonaws.services.evergreen.model.ResolveComponentCandidatesResult;
import com.amazonaws.services.evergreen.model.ResolvedComponentVersion;
import com.amazonaws.services.evergreen.model.ResourceNotFoundException;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionNegotiationException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.lang3.Validate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class ComponentServiceHelper {

    private static final String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);

    private final GreengrassComponentServiceClientFactory clientFactory;
    private final PlatformResolver platformResolver;

    @Inject
    public ComponentServiceHelper(GreengrassComponentServiceClientFactory clientFactory,
                                  PlatformResolver platformResolver) {
        this.clientFactory = clientFactory;
        this.platformResolver = platformResolver;
    }

    /**
     * Resolve a component version with greengrass cloud service. The dependency resolution algorithm goes through the
     * dependencies node by node, so one component got resolve a time.
     *
     * @param componentName             component name to be resolve
     * @param localCandidateVersion     component local candidate version if available
     * @param versionRequirements       component dependents version requirement map
     * @return resolved component version and recipe
     * @throws NoAvailableComponentVersionException if no applicable version available in cloud service
     * @throws ComponentVersionNegotiationException if service exception happens
     */
    ResolvedComponentVersion resolveComponentVersion(String componentName, Semver localCandidateVersion,
            Map<String, Requirement> versionRequirements)
            throws NoAvailableComponentVersionException, ComponentVersionNegotiationException {

        //ComponentPlatform platform = new ComponentPlatform().withAttributes(platformResolver.getCurrentPlatform());
        ComponentPlatform platform =
                new ComponentPlatform().withOs(platformResolver.getCurrentPlatform().get(PlatformResolver.OS_KEY))
                        .withArchitecture(platformResolver.getCurrentPlatform().get(PlatformResolver.ARCHITECTURE_KEY));
        Map<String, String> versionRequirementsInString = versionRequirements.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        ComponentCandidate candidate = new ComponentCandidate().withName(componentName)
                .withVersion(localCandidateVersion == null ? null : localCandidateVersion.getValue())
                .withVersionRequirements(versionRequirementsInString);
        ResolveComponentCandidatesRequest request = new ResolveComponentCandidatesRequest().withPlatform(platform)
                .withComponentCandidates(Collections.singletonList(candidate));

        ResolveComponentCandidatesResult result;
        try {
            result = clientFactory.getCmsClient().resolveComponentCandidates(request);
        } catch (ResourceNotFoundException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                    .log("No applicable version found in cloud registry");
            throw new NoAvailableComponentVersionException(String.format(
                    "No applicable version found in cloud registry for component: '%s' satisfying requirement: '%s'.",
                    componentName, versionRequirements), e);
        } catch (AmazonClientException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                    .log("Failed to get result from Greengrass cloud when resolving component");
            throw new ComponentVersionNegotiationException(
                    String.format("Failed to get result from Greengrass cloud when resolving component: '%s'.",
                                  componentName), e);
        }

        Validate.isTrue(
                result.getResolvedComponentVersions() != null && result.getResolvedComponentVersions().size() == 1,
                "Component service returns invalid response. It should have one resolved component version");
        return result.getResolvedComponentVersions().get(0);
    }

    /**
     * Download a package recipe.
     *
     * @param componentIdentifier identifier of the recipe to be downloaded
     * @return recipe
     * @throws PackageDownloadException if downloading fails
     */
    public String downloadPackageRecipeAsString(ComponentIdentifier componentIdentifier)
            throws PackageDownloadException {
        GetComponentVersionDeprecatedRequest getComponentVersionRequest =
                new GetComponentVersionDeprecatedRequest().withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString())
                        .withType(RecipeFormatType.YAML);

        GetComponentVersionDeprecatedResult getPackageResult =
                download(getComponentVersionRequest, componentIdentifier);
        return StandardCharsets.UTF_8.decode(getPackageResult.getRecipe()).toString();
    }

    private GetComponentVersionDeprecatedResult download(GetComponentVersionDeprecatedRequest r, ComponentIdentifier id)
            throws PackageDownloadException {
        try {
            return clientFactory.getCmsClient().getComponentVersionDeprecated(r);
        } catch (AmazonClientException e) {
            // TODO: [P41215221]: Properly handle all retryable/nonretryable exceptions
            String errorMsg = String.format(PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT, id);
            throw new PackageDownloadException(errorMsg, e);
        }
    }
}
