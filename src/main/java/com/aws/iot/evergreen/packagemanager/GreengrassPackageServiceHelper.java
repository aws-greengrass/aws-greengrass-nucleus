package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.CommitComponentRequest;
import com.amazonaws.services.evergreen.model.CommitComponentResult;
import com.amazonaws.services.evergreen.model.ComponentNameVersion;
import com.amazonaws.services.evergreen.model.CreateComponentArtifactUploadUrlRequest;
import com.amazonaws.services.evergreen.model.CreateComponentArtifactUploadUrlResult;
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
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class GreengrassPackageServiceHelper {

    private static final String PACKAGE_RECIPE_PARSING_EXCEPTION_FMT = "Error parsing downloaded recipe for package %s";
    private static final ObjectMapper RECIPE_SERIALIZER = SerializerFactory.getRecipeSerializer();
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

        try {
            FindComponentVersionsByPlatformResult findComponentResult =
                    evgCmsClient.findComponentVersionsByPlatform(findComponentRequest);

            List<ResolvedComponent> componentSelectedMetadataList = findComponentResult.getComponents();

            return componentSelectedMetadataList.stream().map(componentMetadata -> {
                PackageIdentifier packageIdentifier
                        = new PackageIdentifier(componentMetadata.getComponentName(),
                                                new Semver(componentMetadata.getComponentVersion()),
                                                componentMetadata.getScope());
                return new PackageMetadata(packageIdentifier, componentMetadata.getDependencies().stream().collect(
                        Collectors.toMap(ComponentNameVersion::getComponentName,
                                         ComponentNameVersion::getComponentVersionConstraint)));
            }).collect(Collectors.toList());
        } catch (AmazonClientException e) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            throw new PackageDownloadException("No valid versions were found for this package based on "
                                                       + "provided requirement", e);
        }
    }

    /**
     * Download a package recipe.
     *
     * @param packageIdentifier identifier of the recipe to be downloaded
     * @return recipe
     * @throws PackageDownloadException if downloading fails
     * @throws PackageLoadingException  if parsing the recipe fails
     */
    public PackageRecipe downloadPackageRecipe(PackageIdentifier packageIdentifier)
            throws PackageDownloadException, PackageLoadingException {
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

        try {
            ByteBuffer recipeBuf = getPackageResult.getRecipe();
            return RECIPE_SERIALIZER.readValue(new ByteBufferBackedInputStream(recipeBuf), PackageRecipe.class);
        } catch (IOException e) {
            String errorMsg = String.format(PACKAGE_RECIPE_PARSING_EXCEPTION_FMT, packageIdentifier);
            throw new PackageLoadingException(errorMsg, e);
        }
    }

    /**
     * Create a component with the given recipe file.
     *
     * @param cmsClient client of Component Management Service
     * @param recipeFilePath the path to the component recipe file
     * @return {@link CreateComponentResult}
     * @throws IOException if file reading fails
     */
    public static CreateComponentResult createComponent(AWSEvergreen cmsClient,
                                                        Path recipeFilePath) throws IOException {
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(recipeFilePath));

        CreateComponentRequest createComponentRequest = new CreateComponentRequest().withRecipe(recipeBuf);
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentResult createComponentResult = cmsClient.createComponent(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }

    /**
     * Upload component artifacts for the specified component.
     *
     * @param cmsClient client of Component Management Service
     * @param artifact artifact file
     * @param componentName name of the component that requires the artifact
     * @param componentVersion version of the component that requires the artifact
     * @throws IOException if file upload fails
     */
    public static void createAndUploadComponentArtifact(AWSEvergreen cmsClient, File artifact,
                                                        String componentName, String componentVersion)
            throws IOException {
        if (skipComponentArtifactUpload(artifact)) {
            logger.atDebug("upload-component-artifact").kv("filePath",  artifact.getAbsolutePath())
                    .log("Skip artifact upload. Not a regular file");
            return;
        }
        logger.atDebug("upload-component-artifact").kv("artifactName", artifact.getName())
                .kv("filePath", artifact.getAbsolutePath()).log();
        CreateComponentArtifactUploadUrlResult artifactUploadUrlResult = createComponentArtifactUploadUrl(cmsClient,
                componentName, componentVersion, artifact.getName());
        uploadComponentArtifact(artifactUploadUrlResult.getUrl(), artifact);
    }

    protected static CreateComponentArtifactUploadUrlResult createComponentArtifactUploadUrl(
            AWSEvergreen cmsClient, String componentName,
            String componentVersion, String artifactName) {
        CreateComponentArtifactUploadUrlRequest artifactUploadUrlRequest = new CreateComponentArtifactUploadUrlRequest()
                .withComponentName(componentName).withComponentVersion(componentVersion)
                .withArtifactName(artifactName);
        return cmsClient.createComponentArtifactUploadUrl(artifactUploadUrlRequest);
    }

    protected static void uploadComponentArtifact(String uploadUrl, File artifact) throws IOException {
        URL s3PreSignedURL = new URL(uploadUrl);
        uploadComponentArtifact(s3PreSignedURL, artifact);
    }

    protected static void uploadComponentArtifact(URL uploadUrl, File artifact) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uploadUrl.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.connect();

        try (BufferedOutputStream bos = new BufferedOutputStream(connection.getOutputStream())) {
            long length = Files.copy(artifact.toPath(), bos);
            bos.flush();
            logger.atDebug("upload-component-artifact").kv("artifactName", artifact.getName())
                    .kv("fileSize", length).kv("status", connection.getResponseMessage()).log();
        }
    }

    /**
     * Commit a component of the given name and version.
     *
     * @param cmsClient client of Component Management Service
     * @param componentName name of the component to commit
     * @param componentVersion version of the component to commit
     * @return {@link CommitComponentResult}
     */
    public static CommitComponentResult commitComponent(AWSEvergreen cmsClient,
                                                        String componentName, String componentVersion) {
        CommitComponentRequest commitComponentRequest = new CommitComponentRequest().withComponentName(componentName)
                .withComponentVersion(componentVersion);
        logger.atDebug("commit-component").kv("request", commitComponentRequest).log();
        CommitComponentResult commitComponentResult = cmsClient.commitComponent(commitComponentRequest);
        logger.atDebug("commit-component").kv("result", commitComponentResult).log();
        return commitComponentResult;
    }

    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient client of Component Management Service
     * @param componentName name of the component to delete
     * @param componentVersion version of the component to delete
     * @return {@link DeleteComponentResult}
     */
    public static DeleteComponentResult deleteComponent(AWSEvergreen cmsClient,
                                                        String componentName, String componentVersion) {
        DeleteComponentRequest deleteComponentRequest = new DeleteComponentRequest()
                .withComponentName(componentName).withComponentVersion(componentVersion);
        logger.atDebug("delete-component").kv("request", deleteComponentRequest).log();
        DeleteComponentResult deleteComponentResult = cmsClient.deleteComponent(deleteComponentRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentResult).log();
        return deleteComponentResult;
    }

    private static boolean skipComponentArtifactUpload(File artifact) {
        return artifact.getName().equals(".DS_Store") || artifact.isDirectory();
    }
}
