/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.greengrassv2.AWSGreengrassV2;
import com.amazonaws.services.greengrassv2.model.ComponentCandidate;
import com.amazonaws.services.greengrassv2.model.ComponentPlatform;
import com.amazonaws.services.greengrassv2.model.CreateComponentVersionRequest;
import com.amazonaws.services.greengrassv2.model.CreateComponentVersionResult;
import com.amazonaws.services.greengrassv2.model.DeleteComponentRequest;
import com.amazonaws.services.greengrassv2.model.DeleteComponentResult;
import com.amazonaws.services.greengrassv2.model.GetComponentRequest;
import com.amazonaws.services.greengrassv2.model.GetComponentResult;
import com.amazonaws.services.greengrassv2.model.RecipeOutputFormat;
import com.amazonaws.services.greengrassv2.model.RecipeSource;
import com.amazonaws.services.greengrassv2.model.ResolveComponentCandidatesRequest;
import com.amazonaws.services.greengrassv2.model.ResolveComponentCandidatesResult;
import com.amazonaws.services.greengrassv2.model.ResolvedComponentVersion;
import com.amazonaws.services.greengrassv2.model.ResourceNotFoundException;
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
import java.util.stream.Collectors;
import javax.inject.Inject;

public class ComponentServiceHelper {

    // TODO : Temporarily using hardcoded account id for the test account and format to construct ARN,
    //  This is being addressed in a separate PR so not duplicating the effort in this PR.
    //  Will be removed before checking in once rebased on mentioned PR
    public static final String ACCOUNT_ID_STR = "698947471564";
    public static final String COMPONENT_ARN_FORMAT = "arn:%s:greengrass:%s:aws:components:foo:versions:1.0.0";
    private static final String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";
    // Service logger instance
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
        ComponentCandidate candidate = new ComponentCandidate().withComponentName(componentName)
                .withComponentVersion(localCandidateVersion == null ? null : localCandidateVersion.getValue())
                .withVersionRequirements(versionRequirementsInString);
        ResolveComponentCandidatesRequest request = new ResolveComponentCandidatesRequest().withPlatform(platform)
                .withComponentCandidates(Collections.singletonList(candidate));
        // TODO: [P41215565]: Switch back deploymentConfigurationId once it's removed from URL path
        // use UUID to avoid ARN complication in URL, deploymentConfigurationId is used for logging purpose
        // in server, so could have this hack now
        //.withDeploymentConfigurationId(UUID.randomUUID().toString());

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
        // TODO : UPDATE_MODEL : use ARN when the PR to handle ARN is checked in
        GetComponentRequest getComponentVersionRequest =
                new GetComponentRequest().withArn(componentIdentifier.getName())
                        .withRecipeOutputFormat(RecipeOutputFormat.YAML);

        GetComponentResult getPackageResult =
                download(getComponentVersionRequest, componentIdentifier);
        return StandardCharsets.UTF_8.decode(getPackageResult.getRecipe()).toString();
    }

    private GetComponentResult download(GetComponentRequest r, ComponentIdentifier id)
            throws PackageDownloadException {
        try {
            return clientFactory.getCmsClient().getComponent(r);
        } catch (AmazonClientException e) {
            // TODO: [P41215221]: Properly handle all retryable/nonretryable exceptions
            String errorMsg = String.format(PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT, id);
            throw new PackageDownloadException(errorMsg, e);
        }
    }

    /**
     * Create a component with the given recipe file.
     *
     * @param cmsClient      client of Component Management Service
     * @param recipeFilePath the path to the component recipe file
     * @return {@link CreateComponentVersionResult}
     * @throws IOException if file reading fails
     */
    // TODO: [P41215855]: Make createComponent method non static
    public static CreateComponentVersionResult createComponent(AWSGreengrassV2 cmsClient, Path recipeFilePath)
            throws IOException {
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(recipeFilePath));
        CreateComponentVersionRequest createComponentRequest =
                new CreateComponentVersionRequest().withRecipeSource(new RecipeSource().withInlineRecipe(recipeBuf));
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentVersionResult createComponentResult = cmsClient.createComponentVersion(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }


    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient        client of Component Management Service
     * @param componentArn   name of the component to delete
     * @return {@link DeleteComponentResult}
     */
    public static DeleteComponentResult deleteComponent(AWSGreengrassV2 cmsClient, String componentArn) {
        // TODO : UPDATE_MODEL : use ARN when the PR to handle ARN is checked in
        DeleteComponentRequest deleteComponentVersionRequest =
                new DeleteComponentRequest().withArn(componentArn);
        logger.atDebug("delete-component").kv("request", deleteComponentVersionRequest).log();
        DeleteComponentResult deleteComponentVersionResult =
                cmsClient.deleteComponent(deleteComponentVersionRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentVersionResult).log();
        return deleteComponentVersionResult;
    }
}
