package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.model.ComponentSelectedMetadata;
import com.amazonaws.services.greengrasscomponentmanagement.model.GetComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.GetComponentResult;
import com.amazonaws.services.greengrasscomponentmanagement.model.ListComponentsRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.ListComponentsResult;
import com.amazonaws.services.greengrasscomponentmanagement.model.RecipeFormatType;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class GreengrassPackageServiceHelper {

    private static String PACKAGE_RECIPE_PARSING_EXCEPTION_FMT = "Error parsing downloaded recipe for package %s";
    private static String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";

    private static final ObjectMapper RECIPE_SERIALIZER = SerializerFactory.getRecipeSerializer();

    // Service logger instance
    protected final Logger logger = LogManager.getLogger(GreengrassPackageServiceHelper.class);

    private final AWSGreengrassComponentManagement evgPmsClient;

    @Inject
    public GreengrassPackageServiceHelper(GreengrassPackageServiceClientFactory clientFactory) {
        this.evgPmsClient = clientFactory.getCmsClient();
    }

    List<PackageMetadata> listAvailablePackageMetadata(String packageName, Requirement versionRequirement) {
        ListComponentsRequest listComponentsRequest =
                new ListComponentsRequest().withComponentName(packageName)
                                           .withComponentVersionConstraint(versionRequirement.toString());
        ListComponentsResult listComponentsResult = evgPmsClient.listComponents(listComponentsRequest);
        List<ComponentSelectedMetadata> componentSelectedMetadataList = listComponentsResult.getComponents();
        return componentSelectedMetadataList.stream().map(componentMetadata -> {
            PackageIdentifier packageIdentifier
                    = new PackageIdentifier(componentMetadata.getComponentName(),
                                            new Semver(componentMetadata.getComponentVersion()),
                                            componentMetadata.getComponentARN());
            // Dependencies map is unused as of now, there's no point in requesting dependencies for
            // ALL package versions at this step, will be filled in later by package manager
            return new PackageMetadata(packageIdentifier, Collections.emptyMap());
        }).collect(Collectors.toList());
    }

    PackageRecipe downloadPackageRecipe(PackageIdentifier packageIdentifier)
            throws PackageDownloadException, PackageLoadingException {
        GetComponentRequest getComponentRequest =
                new GetComponentRequest().withComponentName(packageIdentifier.getName())
                                         .withComponentVersion(packageIdentifier.getVersion().toString())
                                         .withType(RecipeFormatType.YAML);

        GetComponentResult getPackageResult;
        try {
            getPackageResult = evgPmsClient.getComponent(getComponentRequest);
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            String errorMsg = String.format(PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT,
                                            packageIdentifier.getArn());
            logger.atError("download-package-from-greengrass-repo", e)
                  .addKeyValue("packageIdentifier", packageIdentifier)
                  .addKeyValue("errorMessage", errorMsg)
                  .log();
            throw new PackageDownloadException(errorMsg, e);
        }

        try {
            ByteBuffer recipeBuf = getPackageResult.getRecipe();
            return RECIPE_SERIALIZER.readValue(new ByteBufferBackedInputStream(recipeBuf),
                                               PackageRecipe.class);
        } catch (IOException e) {
            String errorMsg = String.format(PACKAGE_RECIPE_PARSING_EXCEPTION_FMT,
                                            packageIdentifier.getArn());
            logger.atError("download-package-from-greengrass-repo", e)
                  .addKeyValue("packageIdentifier", packageIdentifier)
                  .addKeyValue("errorMessage", errorMsg)
                  .log();
            throw new PackageLoadingException(errorMsg, e);
        }
    }
}
