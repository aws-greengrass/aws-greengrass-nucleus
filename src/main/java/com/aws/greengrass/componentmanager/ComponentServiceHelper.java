/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.ComponentCandidate;
import com.amazonaws.services.evergreen.model.ComponentContent;
import com.amazonaws.services.evergreen.model.ComponentPlatform;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentRequest;
import com.amazonaws.services.evergreen.model.DeleteComponentResult;
import com.amazonaws.services.evergreen.model.GetComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentResult;
import com.amazonaws.services.evergreen.model.RecipeFormatType;
import com.amazonaws.services.evergreen.model.ResolveComponentVersionsRequest;
import com.amazonaws.services.evergreen.model.ResolveComponentVersionsResult;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class ComponentServiceHelper {

    private static final String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";
    // Service logger instance
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);

    private final AWSEvergreen evgCmsClient;

    @Inject
    public ComponentServiceHelper(GreengrassComponentServiceClientFactory clientFactory) {
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    /**
     * Resolve a component version with greengrass cloud service.
     * The dependency resolution algorithm goes through the dependencies node by node,
     * so one component got resolve a time.
     * @param componentName component name to be resolve
     * @param localCandidateVersion component local candidate version if available
     * @param versionRequirements component dependents version requirement map
     * @param deploymentConfigurationId deployment configuration id
     * @return resolved component version and recipe
     * @throws NoAvailableComponentVersionException if no applicable version available in cloud service
     * @throws ComponentVersionNegotiationException if service exception happens
     */
    ComponentContent resolveComponentVersion(String componentName, Semver localCandidateVersion,
                                             Map<String, Requirement> versionRequirements,
                                             String deploymentConfigurationId)
            throws NoAvailableComponentVersionException, ComponentVersionNegotiationException {

        // TODO add osVersion and osFlavor once they are supported
        ComponentPlatform platform = new ComponentPlatform().withOs(PlatformResolver.CURRENT_PLATFORM.getOs().getName())
                .withArchitecture(PlatformResolver.CURRENT_PLATFORM.getArchitecture().getName());
        Map<String, String> versionRequirementsInString = versionRequirements.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        ComponentCandidate candidate = new ComponentCandidate().withName(componentName)
                .withVersion(localCandidateVersion == null ? null : localCandidateVersion.getValue())
                .withVersionRequirements(versionRequirementsInString);
        ResolveComponentVersionsRequest request = new ResolveComponentVersionsRequest().withPlatform(platform)
                .withComponentCandidates(Collections.singletonList(candidate))
                // TODO switch back deploymentConfigurationId once it's removed from URL path
                // use UUID to avoid ARN complication in URL, deploymentConfigurationId is used for logging purpose
                // in server, so could have this hack now
                .withDeploymentConfigurationId(UUID.randomUUID().toString());

        ResolveComponentVersionsResult result;
        try {
            result = evgCmsClient.resolveComponentVersions(request);
        } catch (ResourceNotFoundException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                    .log("No available version when resolving component");
            throw new NoAvailableComponentVersionException(
                    String.format("No applicable version of component %s " + "found in cloud registry satisfying %s",
                            componentName, versionRequirements), e);
        } catch (AmazonClientException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                    .log("Server error when resolving component");
            throw new ComponentVersionNegotiationException(
                    String.format("Component service error when resolving %s", componentName), e);
        }

        Validate.isTrue(result.getComponents() != null && result.getComponents().size() == 1,
                "Component service " + "invalid response, it should contain resolved component version");
        return result.getComponents().get(0);
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
        GetComponentRequest getComponentRequest =
                new GetComponentRequest().withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString())
                        .withType(RecipeFormatType.YAML);

        GetComponentResult getPackageResult = download(getComponentRequest, componentIdentifier);
        return StandardCharsets.UTF_8.decode(getPackageResult.getRecipe()).toString();
    }

    private GetComponentResult download(GetComponentRequest r, ComponentIdentifier id) throws PackageDownloadException {
        try {
            return evgCmsClient.getComponent(r);
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            String errorMsg = String.format(PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT, id);
            throw new PackageDownloadException(errorMsg, e);
        }
    }

    /**
     * Create a component with the given recipe file.
     *
     * @param cmsClient      client of Component Management Service
     * @param recipeFilePath the path to the component recipe file
     * @return {@link CreateComponentResult}
     * @throws IOException if file reading fails
     */
    // TODO make this an instance method
    public static CreateComponentResult createComponent(AWSEvergreen cmsClient, Path recipeFilePath)
            throws IOException {
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(recipeFilePath));
        CreateComponentRequest createComponentRequest = new CreateComponentRequest().withRecipe(recipeBuf);
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentResult createComponentResult = cmsClient.createComponent(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }


    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient        client of Component Management Service
     * @param componentName    name of the component to delete
     * @param componentVersion version of the component to delete
     * @return {@link DeleteComponentResult}
     */
    public static DeleteComponentResult deleteComponent(AWSEvergreen cmsClient, String componentName,
                                                        String componentVersion) {
        DeleteComponentRequest deleteComponentRequest =
                new DeleteComponentRequest().withComponentName(componentName).withComponentVersion(componentVersion);
        logger.atDebug("delete-component").kv("request", deleteComponentRequest).log();
        DeleteComponentResult deleteComponentResult = cmsClient.deleteComponent(deleteComponentRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentResult).log();
        return deleteComponentResult;
    }
}
