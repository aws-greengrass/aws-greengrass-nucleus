/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.MissingRequiredComponentsException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.docker.DockerImageDownloader;
import com.aws.greengrass.componentmanager.plugins.docker.Image;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.S3SdkClientFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.plugins.docker.DockerApplicationManagerService.DOCKER_MANAGER_PLUGIN_SERVICE_NAME;
import static com.aws.greengrass.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;

public class ArtifactDownloaderFactory {
    private static final String GREENGRASS_SCHEME = "GREENGRASS";
    private static final String S3_SCHEME = "S3";
    public static final String DOCKER_SCHEME = "DOCKER";
    private static final List<String> SUPPORTED_URI_SCHEMES = Arrays.asList(GREENGRASS_SCHEME, S3_SCHEME,
            DOCKER_SCHEME);

    static final String TOKEN_EXCHANGE_SERVICE_REQUIRED_ERROR_MSG =
            String.format("Deployments containing private ECR Docker artifacts must include the %s component",
                    TOKEN_EXCHANGE_SERVICE_TOPICS);
    static final String DOCKER_PLUGIN_REQUIRED_ERROR_MSG =
            String.format("Deployments containing docker artifacts must include the %s component",
                    DOCKER_MANAGER_PLUGIN_SERVICE_NAME);

    private final S3SdkClientFactory s3ClientFactory;

    private final GreengrassServiceClientFactory clientFactory;

    private final ComponentStore componentStore;

    private final Context context;

    private final DeviceConfiguration deviceConfiguration;

    /**
     * ArtifactDownloaderFactory constructor.
     *
     * @param s3SdkClientFactory                      s3SdkClientFactory
     * @param greengrassServiceClientFactory greengrassComponentServiceClientFactory
     * @param componentStore                          componentStore
     * @param context                                 context
     * @param deviceConfiguration                     deviceConfiguration
     */
    @Inject
    public ArtifactDownloaderFactory(S3SdkClientFactory s3SdkClientFactory,
                                     GreengrassServiceClientFactory greengrassServiceClientFactory,
                                     ComponentStore componentStore,
                                     Context context,
                                     DeviceConfiguration deviceConfiguration) {
        this.s3ClientFactory = s3SdkClientFactory;
        this.clientFactory = greengrassServiceClientFactory;
        this.componentStore = componentStore;
        this.context = context;
        this.deviceConfiguration = deviceConfiguration;
    }

    /**
     * Return the artifact downloader instance.
     * @param identifier componentIdentifier
     * @param artifact componentArtifact
     * @param artifactDir directory to download artifact to
     * @return Artifact downloader
     * @throws PackageLoadingException throw if URI scheme not supported
     * @throws InvalidArtifactUriException throw if s3 url not valid
     */
    public ArtifactDownloader getArtifactDownloader(ComponentIdentifier identifier, ComponentArtifact artifact,
                                                    Path artifactDir)
            throws PackageLoadingException, InvalidArtifactUriException {
        URI artifactUri = artifact.getArtifactUri();
        String scheme = artifactUri.getScheme() == null ? null : artifactUri.getScheme().toUpperCase();
        if (GREENGRASS_SCHEME.equals(scheme)) {
            return new GreengrassRepositoryDownloader(clientFactory, identifier, artifact, artifactDir, componentStore,
                    deviceConfiguration);
        }
        if (S3_SCHEME.equals(scheme)) {
            return new S3Downloader(s3ClientFactory, identifier, artifact, artifactDir, componentStore);
        }
        // TODO : Needs to be moved out into a different mechanism where when loaded via a plugin,
        //  an artifact downloader can register itself and be discoverable here.
        if (DOCKER_SCHEME.equals(scheme)) {
            return new DockerImageDownloader(identifier, artifact, artifactDir, context, componentStore);
        }
        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme),
                DeploymentErrorCode.UNSUPPORTED_ARTIFACT_SCHEME);
    }

    /**
     * Check if all plugins that are required for downloading artifacts of other components are included in the
     * deployment.
     *
     * @param artifacts    all artifacts belonging to a component
     * @param currentComponentId {@link ComponentIdentifier} for the component for which the check is being made
     * @param componentIds deployment dependency closure
     * @throws MissingRequiredComponentsException when any required plugins are not included
     * @throws PackageLoadingException            when other errors occur
     */
    public void checkDownloadPrerequisites(List<ComponentArtifact> artifacts,
                                           ComponentIdentifier currentComponentId,
                                           List<ComponentIdentifier> componentIds)
            throws PackageLoadingException, MissingRequiredComponentsException {
        List<String> componentNames =
                componentIds.stream().map(ComponentIdentifier::getName).collect(Collectors.toList());
        for (ComponentArtifact artifact : artifacts) {
            // TODO : Use dedicated component type
            try {
                validateArtifactUri(artifact);
                if (artifact.getArtifactUri().getScheme().equalsIgnoreCase(ArtifactDownloaderFactory.DOCKER_SCHEME)) {
                    if (!componentNames.contains(DOCKER_MANAGER_PLUGIN_SERVICE_NAME)) {
                        throw new MissingRequiredComponentsException(DOCKER_PLUGIN_REQUIRED_ERROR_MSG,
                                DeploymentErrorCode.MISSING_DOCKER_APPLICATION_MANAGER);
                    }
                    Image image = Image.fromArtifactUri(artifact);
                    if (image.getRegistry().isEcrRegistry() && image.getRegistry().isPrivateRegistry()
                            && !componentNames.contains(TOKEN_EXCHANGE_SERVICE_TOPICS)) {
                        throw new MissingRequiredComponentsException(TOKEN_EXCHANGE_SERVICE_REQUIRED_ERROR_MSG,
                                DeploymentErrorCode.MISSING_TOKEN_EXCHANGE_SERVICE);
                    }
                }
            } catch (InvalidArtifactUriException e) {
                throw new PackageLoadingException(String
                        .format("Failed to download due to bad artifact URI: %s for component %s",
                                artifact.getArtifactUri(), currentComponentId.getName()), e);
            }
        }
    }

    private void validateArtifactUri(ComponentArtifact artifact) throws InvalidArtifactUriException {
        if (artifact.getArtifactUri() == null) {
            throw new InvalidArtifactUriException("Artifact URI is empty", DeploymentErrorCode.EMPTY_ARTIFACT_URI);
        } else if (artifact.getArtifactUri().getScheme() == null) {
            throw new InvalidArtifactUriException(
                    String.format("Artifact URI %s is invalid. It does not have a scheme", artifact.getArtifactUri()),
                    DeploymentErrorCode.EMPTY_ARTIFACT_SCHEME);
        } else if (!SUPPORTED_URI_SCHEMES.contains(artifact.getArtifactUri().getScheme().toUpperCase())) {
            throw new InvalidArtifactUriException(
                    String.format("Artifact URI %s is invalid. Only URI schemes " + "supported are %s",
                            artifact.getArtifactUri(), SUPPORTED_URI_SCHEMES),
                    DeploymentErrorCode.UNSUPPORTED_ARTIFACT_SCHEME);
        }
    }
}
