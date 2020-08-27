package com.aws.iot.evergreen.packagemanager;

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
import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
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

public class GreengrassPackageServiceHelper {

    private static final String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";
    // Service logger instance
    protected static final Logger logger = LogManager.getLogger(GreengrassPackageServiceHelper.class);

    private final AWSEvergreen evgCmsClient;

    @Inject
    public GreengrassPackageServiceHelper(GreengrassPackageServiceClientFactory clientFactory) {
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    List<PackageMetadata> listAvailablePackageMetadata(String packageName, Requirement versionRequirement)
            throws PackageDownloadException {
        FindComponentVersionsByPlatformRequest findComponentRequest =
                new FindComponentVersionsByPlatformRequest().withComponentName(packageName)
                                                            .withVersionConstraint(versionRequirement.toString())
                                                            .withPlatform(PlatformResolver.getPlatform());
        List<PackageMetadata> ret = new ArrayList<>();
        try {
            // TODO: If cloud properly sorts the response, then we can optimize this and possibly
            //  not go through all the pagination
            String pagination = null;
            do {
                FindComponentVersionsByPlatformResult findComponentResult =
                        evgCmsClient.findComponentVersionsByPlatform(
                                findComponentRequest.withLastPaginationToken(pagination));
                pagination = findComponentResult.getLastPaginationToken();
                List<ResolvedComponent> componentSelectedMetadataList = findComponentResult.getComponents();

                ret.addAll(componentSelectedMetadataList.stream().map(componentMetadata -> {
                    PackageIdentifier packageIdentifier = new PackageIdentifier(componentMetadata.getComponentName(),
                            new Semver(componentMetadata.getComponentVersion()), componentMetadata.getScope());
                    return new PackageMetadata(packageIdentifier,
                            componentMetadata.getDependencies()
                                             .stream()
                                             .collect(Collectors.toMap(ComponentNameVersion::getComponentName,
                                                     ComponentNameVersion::getComponentVersionConstraint)));
                }).collect(Collectors.toList()));
            } while (pagination != null);

            ret.sort(null);
            return ret;
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            throw new PackageDownloadException(
                    "No valid versions were found for this package based on provided requirement", e);
        }
    }

    /**
     * Download a package recipe.
     *
     * @param packageIdentifier identifier of the recipe to be downloaded
     * @return recipe
     * @throws PackageDownloadException if downloading fails
     */
    public String downloadPackageRecipeAsString(PackageIdentifier packageIdentifier) throws PackageDownloadException {
        GetComponentRequest getComponentRequest =
                new GetComponentRequest().withComponentName(packageIdentifier.getName())
                                         .withComponentVersion(packageIdentifier.getVersion().toString())
                                         .withType(RecipeFormatType.YAML)
                                         .withScope(packageIdentifier.getScope());

        GetComponentResult getPackageResult;
        try {
            getPackageResult = evgCmsClient.getComponent(getComponentRequest);
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            String errorMsg = String.format(PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT, packageIdentifier);
            throw new PackageDownloadException(errorMsg, e);
        }

        return StandardCharsets.UTF_8.decode(getPackageResult.getRecipe()).toString();
    }

    /**
     * Create a component with the given recipe file.
     *
     * @param cmsClient      client of Component Management Service
     * @param recipeFilePath the path to the component recipe file
     * @return {@link CreateComponentResult}
     * @throws IOException if file reading fails
     */
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
