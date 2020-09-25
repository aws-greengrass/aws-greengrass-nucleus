/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.ComponentNameVersion;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentRequest;
import com.amazonaws.services.evergreen.model.DeleteComponentResult;
import com.amazonaws.services.evergreen.model.FindComponentVersionsByPlatformRequest;
import com.amazonaws.services.evergreen.model.FindComponentVersionsByPlatformResult;
import com.amazonaws.services.evergreen.model.GetComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentResult;
import com.amazonaws.services.evergreen.model.RecipeFormatType;
import com.amazonaws.services.evergreen.model.ResolvedComponent;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.models.ComponentIdentifier.PRIVATE_SCOPE;
import static com.aws.greengrass.componentmanager.models.ComponentIdentifier.PUBLIC_SCOPE;

public class ComponentServiceHelper {

    private static final String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";
    // Service logger instance
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);

    private final AWSEvergreen evgCmsClient;

    @Inject
    public ComponentServiceHelper(GreengrassComponentServiceClientFactory clientFactory) {
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    List<ComponentMetadata> listAvailableComponentMetadata(String componentName, Requirement versionRequirement)
            throws PackageDownloadException {
        List<ComponentMetadata> ret = new ArrayList<>();

        FindComponentVersionsByPlatformRequest findComponentRequest =
                new FindComponentVersionsByPlatformRequest().withComponentName(componentName)
                        .withVersionConstraint(versionRequirement.toString())
                        .withOs(PlatformResolver.CURRENT_PLATFORM.getOs().getName())
                        .withArchitecture(PlatformResolver.CURRENT_PLATFORM.getArchitecture().getName());
        try {
            // TODO: If cloud properly sorts the response, then we can optimize this and possibly
            //  not go through all the pagination
            String pagination = null;
            do {
                FindComponentVersionsByPlatformResult findComponentResult = evgCmsClient
                        .findComponentVersionsByPlatform(findComponentRequest.withLastPaginationToken(pagination));
                pagination = findComponentResult.getLastPaginationToken();
                List<ResolvedComponent> componentSelectedMetadataList = findComponentResult.getComponents();

                ret.addAll(componentSelectedMetadataList.stream().map(componentMetadata -> {
                    ComponentIdentifier componentIdentifier =
                            new ComponentIdentifier(componentMetadata.getComponentName(),
                                    new Semver(componentMetadata.getComponentVersion()), componentMetadata.getScope());
                    return new ComponentMetadata(componentIdentifier, componentMetadata.getDependencies().stream()
                            .collect(Collectors.toMap(ComponentNameVersion::getComponentName,
                                    ComponentNameVersion::getComponentVersionConstraint)));
                }).collect(Collectors.toList()));
            } while (pagination != null);
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            throw new PackageDownloadException(
                    "No valid versions were found for this component based on provided requirement: "
                            + findComponentRequest, e);
        }
        ret.sort(null);
        return ret;
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
                        .withType(RecipeFormatType.YAML).withScope(componentIdentifier.getScope());

        // If the scope is listed as PUBLIC, then always try to download the private version first and then PUBLIC
        boolean privateFirst = false;
        if (getComponentRequest.getScope().equalsIgnoreCase(PUBLIC_SCOPE)) {
            privateFirst = true;
            getComponentRequest.withScope(PRIVATE_SCOPE);
        }

        GetComponentResult getPackageResult;
        try {
            getPackageResult = download(getComponentRequest, componentIdentifier);
        } catch (PackageDownloadException e) {
            if (privateFirst) {
                // We tried private and that failed, so try public now
                getPackageResult = download(getComponentRequest.withScope(PUBLIC_SCOPE), componentIdentifier);
            } else {
                throw e;
            }
        }

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
