/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.amazonaws.services.evergreen.model.ComponentContent;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionNegotiationException;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.SizeLimitException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.plugins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.plugins.GreengrassRepositoryDownloader;
import com.aws.greengrass.componentmanager.plugins.S3Downloader;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Permissions;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.ANY_VERSION;

public class ComponentManager implements InjectionActions {
    private static final Logger logger = LogManager.getLogger(ComponentManager.class);
    private static final String GREENGRASS_SCHEME = "GREENGRASS";
    private static final String S3_SCHEME = "S3";
    private static final String PACKAGE_NAME_KEY = "packageName";
    private static final String PACKAGE_IDENTIFIER = "packageIdentifier";

    private static final long DEFAULT_MIN_DISK_AVAIL_BYTES = 1_000_000L;

    private final S3Downloader s3ArtifactsDownloader;
    private final GreengrassRepositoryDownloader greengrassArtifactDownloader;
    private final ComponentServiceHelper componentServiceHelper;
    private final ExecutorService executorService;
    private final ComponentStore componentStore;
    private final Kernel kernel;
    private final Unarchiver unarchiver;
    private final NucleusPaths nucleusPaths;

    @Inject
    @Setter
    private DeviceConfiguration deviceConfiguration;

    /**
     * PackageManager constructor.
     *
     * @param s3ArtifactsDownloader          s3ArtifactsDownloader
     * @param greengrassArtifactDownloader   greengrassArtifactDownloader
     * @param componentServiceHelper         greengrassPackageServiceHelper
     * @param executorService                executorService
     * @param componentStore                 componentStore
     * @param kernel                         kernel
     * @param unarchiver                     unarchiver
     * @param deviceConfiguration            deviceConfiguration
     * @param nucleusPaths                   path library
     */
    @Inject
    public ComponentManager(S3Downloader s3ArtifactsDownloader,
                            GreengrassRepositoryDownloader greengrassArtifactDownloader,
                            ComponentServiceHelper componentServiceHelper,
                            ExecutorService executorService, ComponentStore componentStore, Kernel kernel,
                            Unarchiver unarchiver, DeviceConfiguration deviceConfiguration,
                            NucleusPaths nucleusPaths) {
        this.s3ArtifactsDownloader = s3ArtifactsDownloader;
        this.greengrassArtifactDownloader = greengrassArtifactDownloader;
        this.componentServiceHelper = componentServiceHelper;
        this.executorService = executorService;
        this.componentStore = componentStore;
        this.kernel = kernel;
        this.unarchiver = unarchiver;
        this.deviceConfiguration = deviceConfiguration;
        this.nucleusPaths = nucleusPaths;
    }

    ComponentMetadata resolveComponentVersion(String componentName, Map<String, Requirement> versionRequirements,
                                              String deploymentConfigurationId) throws PackagingException {
        // acquire ever possible local best candidate
        Optional<ComponentIdentifier> localCandidateOptional =
                findLocalBestCandidate(componentName, versionRequirements);
        logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                .kv("localCandidate", localCandidateOptional.orElse(null)).log("Resolve to local version");
        ComponentIdentifier resolvedComponentId;
        if (versionRequirements.containsKey(Deployment.DeploymentType.LOCAL.toString())) {
            // keep using local version if the component is meant to be local override
            resolvedComponentId = localCandidateOptional.orElseThrow(() -> new NoAvailableComponentVersionException(
                    String.format("Component %s is meant to be a local override, but no version can satisfy %s",
                            componentName, versionRequirements)));
        } else {
            // otherwise try to negotiate with cloud
            resolvedComponentId =
                    negotiateVersionWithCloud(componentName, versionRequirements, localCandidateOptional.orElse(null),
                            deploymentConfigurationId);
        }

        return getComponentMetadata(resolvedComponentId);
    }

    private ComponentIdentifier negotiateVersionWithCloud(String componentName,
                                                          Map<String, Requirement> versionRequirements,
                                                          ComponentIdentifier localCandidate,
                                                          String deploymentConfigurationId) throws PackagingException {
        ComponentContent componentContent;

        try {
            componentContent = componentServiceHelper
                    .resolveComponentVersion(componentName, localCandidate == null ? null : localCandidate.getVersion(),
                            versionRequirements, deploymentConfigurationId);
        } catch (ComponentVersionNegotiationException | NoAvailableComponentVersionException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirement", versionRequirements)
                    .kv("localVersion", localCandidate).log("Can't negotiate version with cloud, use local version", e);
            if (localCandidate != null) {
                return localCandidate;
            }
            throw new NoAvailableComponentVersionException(
                    String.format("Can't negotiate component %s version with cloud and no local applicable version "
                                    + "satisfying %s", componentName, versionRequirements), e);
        }

        ComponentIdentifier resolvedComponentId =
                new ComponentIdentifier(componentContent.getName(), new Semver(componentContent.getVersion()));
        String downloadedRecipeContent = StandardCharsets.UTF_8.decode(componentContent.getRecipe()).toString();

        boolean saveContent = true;
        Optional<String> recipeContentOnDevice = componentStore.findComponentRecipeContent(resolvedComponentId);

        if (recipeContentOnDevice.filter(recipe -> recipe.equals(downloadedRecipeContent)).isPresent()) {
            saveContent = false;
        }

        if (saveContent) {
            componentStore.savePackageRecipe(resolvedComponentId, downloadedRecipeContent);
        }

        return resolvedComponentId;
    }

    private Optional<ComponentIdentifier> findLocalBestCandidate(String componentName,
                                                                 Map<String, Requirement> versionRequirements)
            throws PackagingException {
        Requirement req = mergeVersionRequirements(versionRequirements);

        Optional<ComponentIdentifier> optionalActiveComponentId = findActiveAndSatisfiedComponent(componentName, req);

        // use active one if compatible, otherwise check local available ones
        if (optionalActiveComponentId.isPresent()) {
            return optionalActiveComponentId;
        } else {
            return componentStore.findBestMatchAvailableComponent(componentName, req);
        }
    }

    private Requirement mergeVersionRequirements(Map<String, Requirement> versionRequirements) {
        return Requirement.buildNPM(
                versionRequirements.values().stream().map(Requirement::toString).collect(Collectors.joining(" ")));
    }

    private ComponentMetadata getComponentMetadata(ComponentIdentifier componentIdentifier) throws PackagingException {
        // If the component is builtin, then we won't be able to get the metadata from the filesystem,
        // so in that case we will try getting it from builtin. If that fails too, then we just rethrow.
        try {
            return componentStore.getPackageMetadata(componentIdentifier);
        } catch (PackagingException e) {
            ComponentMetadata md =
                    getBuiltinComponentMetadata(componentIdentifier.getName(), componentIdentifier.getVersion());
            if (md != null) {
                return md;
            }
            throw e;
        }
    }

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if they
     * don't exist.
     *
     * @param pkgIds a list of packages.
     * @return a future to notify once this is finished.
     */
    public Future<Void> preparePackages(List<ComponentIdentifier> pkgIds) {
        return executorService.submit(() -> {
            for (ComponentIdentifier componentIdentifier : pkgIds) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                preparePackage(componentIdentifier);
            }
            return null;
        });
    }

    private void preparePackage(ComponentIdentifier componentIdentifier)
            throws PackageLoadingException, PackageDownloadException, InvalidArtifactUriException {
        logger.atInfo().setEventType("prepare-package-start").kv(PACKAGE_IDENTIFIER, componentIdentifier).log();
        try {
            ComponentRecipe pkg = findRecipeDownloadIfNotExisted(componentIdentifier);
            prepareArtifacts(componentIdentifier, pkg.getArtifacts());
            logger.atInfo("prepare-package-finished").kv(PACKAGE_IDENTIFIER, componentIdentifier).log();
        } catch (SizeLimitException e) {
            logger.atError().log("Size limit reached", e);
            throw e;
        } catch (PackageLoadingException | PackageDownloadException e) {
            logger.atError().log("Failed to prepare package {}", componentIdentifier, e);
            throw e;
        }
    }

    // With simplified dependency resolving logic, recipe should be available when resolveComponentVersion,
    // and should be available on device at this step.
    @Deprecated
    private ComponentRecipe findRecipeDownloadIfNotExisted(ComponentIdentifier componentIdentifier)
            throws PackageDownloadException, PackageLoadingException {
        Optional<ComponentRecipe> packageOptional = Optional.empty();
        try {
            packageOptional = componentStore.findPackageRecipe(componentIdentifier);
            logger.atDebug().kv("component", componentIdentifier).log("Loaded from local component store");
        } catch (PackageLoadingException e) {
            logger.atWarn().log("Failed to load component recipe for {}", componentIdentifier, e);
        }

        if (packageOptional.isPresent()) {
            return packageOptional.get();
        }
        String downloadRecipeContent = componentServiceHelper.downloadPackageRecipeAsString(componentIdentifier);
        componentStore.savePackageRecipe(componentIdentifier, downloadRecipeContent);
        logger.atDebug().kv("pkgId", componentIdentifier).log("Downloaded from component service");
        return componentStore.getPackageRecipe(componentIdentifier);
    }

    void prepareArtifacts(ComponentIdentifier componentIdentifier, List<ComponentArtifact> artifacts)
            throws PackageLoadingException, PackageDownloadException, InvalidArtifactUriException {
        if (artifacts == null) {
            logger.atWarn().kv(PACKAGE_IDENTIFIER, componentIdentifier)
                    .log("Artifact list was null, expected non-null and non-empty");
            return;
        }
        Path packageArtifactDirectory = componentStore.resolveArtifactDirectoryPath(componentIdentifier);

        logger.atDebug().setEventType("downloading-package-artifacts")
                .addKeyValue(PACKAGE_IDENTIFIER, componentIdentifier).log();

        for (ComponentArtifact artifact : artifacts) {
            // check disk space before download
            // GG_NEEDS_REVIEW: TODO refactor to check total size of artifacts from all components at once instead of
            //  one by one because all artifacts must fit otherwise the deployment still fails.
            long usableSpaceBytes = componentStore.getUsableSpace();
            if (usableSpaceBytes < DEFAULT_MIN_DISK_AVAIL_BYTES) {
                throw new SizeLimitException(
                        String.format("Disk space critical: %d bytes usable, %d bytes minimum allowed",
                                usableSpaceBytes, DEFAULT_MIN_DISK_AVAIL_BYTES));
            }
            ArtifactDownloader downloader = selectArtifactDownloader(artifact.getArtifactUri());

            if (downloader.downloadRequired(componentIdentifier, artifact, packageArtifactDirectory)) {
                long downloadSize = downloader.getDownloadSize(componentIdentifier, artifact, packageArtifactDirectory);
                long storeContentSize = componentStore.getContentSize();
                if (storeContentSize + downloadSize > getConfiguredMaxSize()) {
                    throw new SizeLimitException(String.format(
                            "Component store size limit reached: %d bytes existing, %d bytes needed"
                                    + ", %d bytes maximum allowed total", storeContentSize, downloadSize,
                            getConfiguredMaxSize()));
                }
                try {
                    downloader.downloadToPath(componentIdentifier, artifact, packageArtifactDirectory);
                } catch (IOException e) {
                    throw new PackageDownloadException(
                            String.format("Failed to download component %s artifact %s", componentIdentifier, artifact),
                            e);
                }
            }
            File artifactFile = downloader.getArtifactFile(packageArtifactDirectory, artifact, componentIdentifier);
            if (artifactFile != null) {
                // TODO: Change permissions - set world readable until artifact permissions can be set via model
                try {
                    Permissions.setArtifactPermission(artifactFile.toPath());
                } catch (IOException e) {
                    throw new PackageDownloadException(
                            String.format("Failed to change permissions of component %s artifact %s",
                                    componentIdentifier, artifact), e);
                }
            }
            Unarchive unarchive = artifact.getUnarchive();
            if (unarchive == null) {
                unarchive = Unarchive.NONE;
            }

            if (artifactFile != null && !unarchive.equals(Unarchive.NONE)) {
                try {
                    Path unarchivePath = nucleusPaths.unarchiveArtifactPath(componentIdentifier,
                            getFileName(artifactFile));
                    unarchiver.unarchive(unarchive, artifactFile, unarchivePath);
                    try {
                        Permissions.setArtifactPermission(unarchivePath);
                    } catch (IOException e) {
                        throw new PackageDownloadException(
                                String.format("Failed to change permissions of component %s artifact %s",
                                        componentIdentifier, artifact), e);
                    }
                } catch (IOException e) {
                    throw new PackageDownloadException(
                            String.format("Failed to unarchive component %s artifact %s", componentIdentifier,
                                    artifact), e);
                }
            }
        }
    }

    private long getConfiguredMaxSize() {
        return Coerce.toLong(deviceConfiguration.getComponentStoreMaxSizeBytes());
    }

    /**
     * Delete stale versions from local store.
     *
     * @throws PackageLoadingException if I/O exception during deletion
     */
    public void cleanupStaleVersions() throws PackageLoadingException {
        logger.atInfo("cleanup-stale-versions-start").log();
        Map<String, Set<String>> versionsToKeep = getVersionsToKeep();
        Map<String, Set<String>> versionsToRemove = componentStore.listAvailableComponentVersions();
        // remove all local versions that does not exist in versionsToKeep
        for (Map.Entry<String, Set<String>> localVersions : versionsToRemove.entrySet()) {
            String compName = localVersions.getKey();
            Set<String> removeVersions = new HashSet<>(localVersions.getValue());
            if (versionsToKeep.containsKey(compName)) {
                removeVersions.removeAll(versionsToKeep.get(compName));
            }
            for (String compVersion : removeVersions) {
                componentStore
                        .deleteComponent(new ComponentIdentifier(compName, new Semver(compVersion)));
            }
        }
        logger.atInfo("cleanup-stale-versions-finish").log();
    }

    /**
     * Query service config to obtain non-stale versions of components which should not be cleaned up.
     *
     * @return mapping from component name string to collection of non-stale version strings
     */
    public Map<String, Set<String>> getVersionsToKeep() {
        Map<String, Set<String>> result = new HashMap<>();
        for (GreengrassService service : kernel.orderedDependencies()) {
            Set<String> nonStaleVersions = new HashSet<>();
            Topic versionTopic = service.getServiceConfig().find(VERSION_CONFIG_KEY);
            Topic prevVersionTopic = service.getServiceConfig().find(PREV_VERSION_CONFIG_KEY);
            if (versionTopic != null) {
                String version = (String) versionTopic.getOnce();
                nonStaleVersions.add(version);
            }
            if (prevVersionTopic != null) {
                String version = (String) prevVersionTopic.getOnce();
                nonStaleVersions.add(version);
            }
            result.put(service.getName(), nonStaleVersions);
        }
        return result;
    }

    private ArtifactDownloader selectArtifactDownloader(URI artifactUri) throws PackageLoadingException {
        String scheme = artifactUri.getScheme() == null ? null : artifactUri.getScheme().toUpperCase();
        if (GREENGRASS_SCHEME.equals(scheme)) {
            return greengrassArtifactDownloader;
        }
        if (S3_SCHEME.equals(scheme)) {
            return s3ArtifactsDownloader;
        }
        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme));
    }

    /**
     * Find the active version for a package.
     *
     * @param packageName the package name
     * @return Optional of version; Empty if no active version for this package found.
     */
    private Optional<Semver> findActiveVersion(final String packageName) {
        if (kernel.findServiceTopic(packageName) == null) {
            return Optional.empty();
        }

        try {
            GreengrassService service = kernel.locate(packageName);
            return Optional.ofNullable(getPackageVersionFromService(service));
        } catch (ServiceLoadException e) {
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .log("Didn't find a active service for this package running in the kernel.");
            return Optional.empty();
        }
    }

    /**
     * Get the package version from the active Greengrass service.
     *
     * @param service the active Greengrass service
     * @return the package version from the active Greengrass service
     */
    Semver getPackageVersionFromService(final GreengrassService service) {
        Topic versionTopic = service.getServiceConfig().findLeafChild(VERSION_CONFIG_KEY);

        if (versionTopic == null) {
            return null;
        }

        return new Semver(Coerce.toString(versionTopic));
    }

    /**
     * Find the package metadata for a package if it's active version satisfies the requirement.
     *
     * @param componentName the component name
     * @param requirement   the version requirement
     * @return Optional of the package metadata for the package; empty if this package doesn't have active version or
     *         the active version doesn't satisfy the requirement.
     * @throws PackagingException if fails to find the target recipe or parse the recipe
     */
    private Optional<ComponentMetadata> findActiveAndSatisfiedPackageMetadata(String componentName,
                                                                              Requirement requirement)
            throws PackagingException {
        Optional<Semver> activeVersionOptional = findActiveVersion(componentName);

        if (!activeVersionOptional.isPresent()) {
            return Optional.empty();
        }

        Semver activeVersion = activeVersionOptional.get();

        if (!requirement.isSatisfiedBy(activeVersion)) {
            return Optional.empty();
        }

        return Optional.of(getComponentMetadata(new ComponentIdentifier(componentName, activeVersion)));
    }

    /**
     * Get active component version and dependencies, the component version satisfies dependent version requirements.
     *
     * @param componentName  component name to be queried for active version
     * @param requirementMap component dependents version requirement map
     * @return active component metadata which satisfies version requirement
     * @throws PackagingException no available version exception
     */
    ComponentMetadata getActiveAndSatisfiedComponentMetadata(String componentName,
                                                             Map<String, Requirement> requirementMap)
            throws PackagingException {
        return getActiveAndSatisfiedComponentMetadata(componentName, mergeVersionRequirements(requirementMap));
    }

    private ComponentMetadata getActiveAndSatisfiedComponentMetadata(String componentName, Requirement requirement)
            throws PackagingException {
        Optional<ComponentMetadata> componentMetadataOptional =
                findActiveAndSatisfiedPackageMetadata(componentName, requirement);
        if (!componentMetadataOptional.isPresent()) {
            throw new NoAvailableComponentVersionException(
                    String.format("There is no version of component %s satisfying %s", componentName, requirement));
        }

        return componentMetadataOptional.get();
    }

    private Optional<ComponentIdentifier> findActiveAndSatisfiedComponent(String componentName,
                                                                          Requirement requirement) {
        Optional<Semver> activeVersionOptional = findActiveVersion(componentName);

        return activeVersionOptional.filter(requirement::isSatisfiedBy)
                .map(version -> new ComponentIdentifier(componentName, version));
    }

    @Nullable
    private ComponentMetadata getBuiltinComponentMetadata(String packageName, Semver activeVersion) {
        try {
            GreengrassService service = kernel.locate(packageName);
            if (!service.isBuiltin()) {
                return null;
            }

            Map<String, String> deps = new HashMap<>();
            service.forAllDependencies(d -> deps.put(d.getServiceName(), ANY_VERSION));

            return new ComponentMetadata(new ComponentIdentifier(packageName, activeVersion), deps);
        } catch (ServiceLoadException e) {
            return null;
        }
    }

    private String getFileName(File f) {
        String fileName = f.getName();
        if (fileName.indexOf('.') > 0) {
            return fileName.substring(0, fileName.lastIndexOf('.'));
        } else {
            return fileName;
        }
    }
}
