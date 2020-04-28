package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.greengrasspackagemanagement.AWSGreengrassPackageManagement;
import com.amazonaws.services.greengrasspackagemanagement.model.GetPackageRequest;
import com.amazonaws.services.greengrasspackagemanagement.model.GetPackageResult;
import com.amazonaws.services.greengrasspackagemanagement.model.RecipeFormatType;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.inject.Inject;

public class GreengrassPackageServiceHelper {

    private static String PACKAGE_RECIPE_PARSING_EXCEPTION_FMT = "Error parsing downloaded recipe for package %s";
    private static String PACKAGE_RECIPE_DOWNLOAD_EXCEPTION_FMT = "Error downloading recipe for package %s";

    private static final ObjectMapper RECIPE_SERIALIZER = SerializerFactory.getRecipeSerializer();

    // Service logger instance
    protected final Logger logger = LogManager.getLogger(GreengrassPackageServiceHelper.class);

    private final AWSGreengrassPackageManagement evgPmsClient;

    @Inject
    public GreengrassPackageServiceHelper(GreengrassPackageServiceClientFactory clientFactory) {
        this.evgPmsClient = clientFactory.getPmsClient();
    }

    PackageRecipe downloadPackageRecipe(PackageIdentifier packageIdentifier)
            throws PackageDownloadException, PackageLoadingException {
        GetPackageRequest getPackageRequest =
                new GetPackageRequest().withPackageARN(packageIdentifier.getArn())
                                       .withType(RecipeFormatType.YAML);

        GetPackageResult getPackageResult;
        try {
            getPackageResult = evgPmsClient.getPackage(getPackageRequest);
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
