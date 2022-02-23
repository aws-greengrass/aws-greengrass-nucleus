/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.MissingRequiredComponentsException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.SizeLimitException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Permissions;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPackageManager;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2data.model.ResolvedComponentVersion;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.ANY_VERSION;
import static org.apache.commons.io.FileUtils.ONE_MB;

public class ComponentManager implements InjectionActions {
    private static final Logger logger = LogManager.getLogger(ComponentManager.class);
    private static final String PACKAGE_NAME_KEY = "packageName";
    private static final String PACKAGE_IDENTIFIER = "packageIdentifier";
    private static final String COMPONENT_STR = "component";

    private static final long DEFAULT_MIN_DISK_AVAIL_BYTES = 20 * ONE_MB;
    protected static final String COMPONENT_NAME = "componentName";
    public static final long DEFAULT_ANDROID_PACKAGE_UNINSTALL_MS = 120 * 1000;

    private final ArtifactDownloaderFactory artifactDownloaderFactory;
    private final ComponentServiceHelper componentServiceHelper;
    private final ExecutorService executorService;
    private final ComponentStore componentStore;
    private final Kernel kernel;
    private final Unarchiver unarchiver;
    private final NucleusPaths nucleusPaths;
    private final Platform platform;
    // Setter for unit tests
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1))
                    .maxRetryInterval(Duration.ofMinutes(1)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class)).build();

    @Inject
    @Setter
    private DeviceConfiguration deviceConfiguration;

    /**
     * ComponentManager constructor.
     *
     * @param artifactDownloaderFactory artifactDownloaderFactory
     * @param componentServiceHelper    greengrassPackageServiceHelper
     * @param executorService           executorService
     * @param componentStore            componentStore
     * @param kernel                    kernel
     * @param unarchiver                unarchiver
     * @param deviceConfiguration       deviceConfiguration
     * @param nucleusPaths              path library
     */
    @Inject
    public ComponentManager(ArtifactDownloaderFactory artifactDownloaderFactory,
                            ComponentServiceHelper componentServiceHelper, ExecutorService executorService,
                            ComponentStore componentStore, Kernel kernel, Unarchiver unarchiver,
                            DeviceConfiguration deviceConfiguration, NucleusPaths nucleusPaths) {
        this(artifactDownloaderFactory, componentServiceHelper, executorService, componentStore,
                kernel, unarchiver, deviceConfiguration, nucleusPaths, Platform.getInstance());
    }

    /**
     * Constructor for unit tests.
     *
     * @param artifactDownloaderFactory artifactDownloaderFactory
     * @param componentServiceHelper    greengrassPackageServiceHelper
     * @param executorService           executorService
     * @param componentStore            componentStore
     * @param kernel                    kernel
     * @param unarchiver                unarchiver
     * @param deviceConfiguration       deviceConfiguration
     * @param nucleusPaths              path library
     * @param platform                  platform
     */
    public ComponentManager(ArtifactDownloaderFactory artifactDownloaderFactory,
                            ComponentServiceHelper componentServiceHelper, ExecutorService executorService,
                            ComponentStore componentStore, Kernel kernel, Unarchiver unarchiver,
                            DeviceConfiguration deviceConfiguration, NucleusPaths nucleusPaths, Platform platform) {
        this.artifactDownloaderFactory = artifactDownloaderFactory;
        this.componentServiceHelper = componentServiceHelper;
        this.executorService = executorService;
        this.componentStore = componentStore;
        this.kernel = kernel;
        this.unarchiver = unarchiver;
        this.deviceConfiguration = deviceConfiguration;
        this.nucleusPaths = nucleusPaths;
        this.platform = platform;
    }

    ComponentMetadata resolveComponentVersion(String componentName, Map<String, Requirement> versionRequirements)
            throws InterruptedException, PackagingException {
        logger.atDebug().setEventType("resolve-component-version-start").kv(COMPONENT_STR, componentName)
                .kv("versionRequirements", versionRequirements).log("Resolving component version starts");

        // Find best local candidate
        Optional<ComponentIdentifier> localCandidateOptional =
                findBestCandidateLocally(componentName, versionRequirements);

        if (localCandidateOptional.isPresent()) {
            logger.atInfo().kv("LocalCandidateId", localCandidateOptional.get())
                    .log("Found the best local candidate that satisfies the requirement.");
        } else {
            logger.atInfo().log("Can't find a local candidate that satisfies the requirement.");
        }
        ComponentIdentifier resolvedComponentId;

        if (versionRequirements.containsKey(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME)
                && localCandidateOptional.isPresent()) {
            // If local group has a requirement and a satisfying local version presents, use it and don't negotiate with
            // cloud.
            logger.atInfo().log("Local group has a requirement and found satisfying local candidate. Using the local"
                    + " candidate as the resolved version without negotiating with cloud.");
            resolvedComponentId = localCandidateOptional.get();
        } else {
            // Otherwise try to negotiate with cloud
            if (deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
                logger.atDebug().setEventType("negotiate-version-with-cloud-start")
                        .log("Negotiating version with cloud");
                resolvedComponentId = negotiateVersionWithCloud(componentName, versionRequirements,
                        localCandidateOptional.orElse(null));
                logger.atDebug().setEventType("negotiate-version-with-cloud-end").log("Negotiated version with cloud");
            } else {
                // Device running offline. Use the local candidate if present, otherwise fails
                if (localCandidateOptional.isPresent()) {
                    logger.atInfo().log("Device is running offline and found satisfying local candidate. Using the "
                            + "local candidate as the resolved version without negotiating with cloud.");
                    resolvedComponentId = localCandidateOptional.get();
                } else {
                    throw new NoAvailableComponentVersionException(
                            "Device is configured to run offline and no local component version satisfies the "
                                    + "requirements.", componentName, versionRequirements);
                }
            }
        }

        logger.atInfo().setEventType("resolve-component-version-end").kv("ResolvedComponent", resolvedComponentId)
                .log("Resolved component version.");

        return getComponentMetadata(resolvedComponentId);
    }

    private void removeRecipeDigestIfExists(ComponentIdentifier componentIdentifier) {
        // clean up digest from store
        Topic digestTopic = kernel.getMain().getRuntimeConfig()
                .find(Kernel.SERVICE_DIGEST_TOPIC_KEY, componentIdentifier.toString());
        if (digestTopic != null) {
            digestTopic.remove();
            logger.atDebug().kv(COMPONENT_STR, componentIdentifier).log("Remove digest from store");
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException", "PMD.NullAssignment",
            "PMD.AvoidInstanceofChecksInCatchClause", "PMD.PreserveStackTrace"})
    private ComponentIdentifier negotiateVersionWithCloud(String componentName,
            Map<String, Requirement> versionRequirements,
            ComponentIdentifier localCandidate)
            throws PackagingException, InterruptedException {
        ResolvedComponentVersion resolvedComponentVersion;

        if (localCandidate == null) {
            try {
                resolvedComponentVersion = RetryUtils.runWithRetry(clientExceptionRetryConfig,
                        () -> componentServiceHelper.resolveComponentVersion(componentName, null, versionRequirements),
                        "resolve-component-version", logger);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // Don't bother logging the full stacktrace when it is NoAvailableComponentVersionException since we
                // know the reason for that error
                logger.atError().setCause(e instanceof NoAvailableComponentVersionException ? null : e)
                        .kv(COMPONENT_NAME, componentName)
                        .kv("versionRequirement", versionRequirements)
                        .log("Failed to negotiate version with cloud and no local version to fall back to");

                // If it is NoAvailableComponentVersionException then we do not need to set the cause, because we
                // know what the cause is.
                if (e instanceof NoAvailableComponentVersionException) {
                    throw new NoAvailableComponentVersionException(
                            "No local or cloud component version satisfies the requirements.", componentName,
                            versionRequirements);
                } else {
                    throw new NoAvailableComponentVersionException(
                            "No local or cloud component version satisfies the requirements.", componentName,
                            versionRequirements, e);
                }
            }
        } else {
            try {
                resolvedComponentVersion = componentServiceHelper
                        .resolveComponentVersion(componentName, localCandidate.getVersion(), versionRequirements);
            } catch (Exception e) {
                // Don't bother logging the full stacktrace when it is NoAvailableComponentVersionException since we
                // know the reason for that error
                logger.atInfo().setCause(e instanceof NoAvailableComponentVersionException ? null : e)
                        .kv(COMPONENT_NAME, componentName)
                        .kv("versionRequirement", versionRequirements).kv("localVersion", localCandidate)
                        .log("Failed to negotiate version with cloud and fall back to use the local version");
                return localCandidate;
            }
        }

        ComponentIdentifier resolvedComponentId = new ComponentIdentifier(resolvedComponentVersion.componentName(),
                new Semver(resolvedComponentVersion.componentVersion()));
        String downloadedRecipeContent =
                StandardCharsets.UTF_8.decode(resolvedComponentVersion.recipe().asByteBuffer()).toString();
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe cloudResolvedRecipe =
                RecipeLoader.parseRecipe(downloadedRecipeContent, RecipeLoader.RecipeFormat.JSON); // cloud sends JSON

        // Persist the recipe
        String savedRecipeContent = componentStore.saveComponentRecipe(cloudResolvedRecipe);

        // Since plugin runs in the same JVM as Nucleus does, we need to calculate the digest for its recipe and
        // persist it, so that we can use it to detect and prevent a tampered plugin (recipe) gets loaded
        storeRecipeDigestInConfigStoreForPlugin(cloudResolvedRecipe, savedRecipeContent);

        // Save the arn to the recipe meta data file
        componentStore.saveRecipeMetadata(resolvedComponentId, new RecipeMetadata(resolvedComponentVersion.arn()));

        return resolvedComponentId;
    }


    private void storeRecipeDigestInConfigStoreForPlugin(
            com.amazon.aws.iot.greengrass.component.common.ComponentRecipe componentRecipe, String recipeContent)
            throws PackageLoadingException {
        ComponentIdentifier componentIdentifier =
                new ComponentIdentifier(componentRecipe.getComponentName(), componentRecipe.getComponentVersion());
        if (componentRecipe.getComponentType() != ComponentType.PLUGIN) {
            logger.atDebug().kv(COMPONENT_STR, componentIdentifier)
                    .log("Skip storing digest as component is not plugin");
            return;
        }
        try {
            String digest = Digest.calculate(recipeContent);
            kernel.getMain().getRuntimeConfig().lookup(Kernel.SERVICE_DIGEST_TOPIC_KEY, componentIdentifier.toString())
                    .withValue(digest);
            logger.atDebug().kv(COMPONENT_STR, componentIdentifier).kv("digest", digest).log("Saved plugin digest");
        } catch (NoSuchAlgorithmException e) {
            // This should never happen as SHA-256 is mandatory for every default JVM provider
            throw new PackageLoadingException("No security provider found for message digest", e);
        }
    }

    private Optional<ComponentIdentifier> findBestCandidateLocally(String componentName,
                                                                   Map<String, Requirement> versionRequirements)
            throws PackagingException {
        logger.atDebug().kv("ComponentName", componentName).kv("VersionRequirements", versionRequirements)
                .log("Searching for best candidate locally on the device.");

        Requirement req = mergeVersionRequirements(versionRequirements);

        Optional<ComponentIdentifier> optionalActiveComponentId = findActiveAndSatisfiedComponent(componentName, req);

        // use active one if compatible, otherwise check local available ones
        if (optionalActiveComponentId.isPresent()) {
            logger.atInfo().kv("ComponentIdentifier", optionalActiveComponentId.get())
                    .log("Found running component which meets the requirement and use it.");
            return optionalActiveComponentId;

        } else {
            logger.atInfo()
                    .log("No running component satisfies the requirement. Searching in the local component store.");
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
                    logger.atInfo().log("Interrupted while preparing artifact for component {}.",
                            componentIdentifier.getName());
                    return null;
                }
                try {
                    preparePackage(componentIdentifier);
                } catch (InterruptedException ie) {
                    logger.atInfo().log("Interrupted while preparing artifact for component {}.",
                            componentIdentifier.getName());
                    return null;
                }
            }
            return null;
        });
    }

    /**
     * Check if all plugins that are required to execute pre-merge steps for other components are included
     * in the deployment.
     *
     * @param componentIds deployment dependency closure
     * @throws MissingRequiredComponentsException when any required plugins are not included
     * @throws PackageLoadingException            when other errors occur
     */
    public void checkPreparePackagesPrerequisites(List<ComponentIdentifier> componentIds)
            throws MissingRequiredComponentsException, PackageLoadingException {
        for (ComponentIdentifier componentId : componentIds) {
            Optional<ComponentRecipe> recipeOption = componentStore.findPackageRecipe(componentId);
            if (!recipeOption.isPresent()) {
                throw new PackageLoadingException(
                        String.format("Unexpected error - cannot find recipe for a component to be prepared - %s",
                                componentId));
            }
            artifactDownloaderFactory
                    .checkDownloadPrerequisites(recipeOption.get().getArtifacts(), componentId, componentIds);
        }
    }

    private void preparePackage(ComponentIdentifier componentIdentifier)
            throws PackageLoadingException, PackageDownloadException, InvalidArtifactUriException,
            InterruptedException {
        logger.atInfo().setEventType("prepare-package-start").kv(PACKAGE_IDENTIFIER, componentIdentifier).log();
        try {
            ComponentRecipe pkg = componentStore.getPackageRecipe(componentIdentifier);
            prepareArtifacts(componentIdentifier, pkg.getArtifacts());
            logger.atDebug("prepare-package-finished").kv(PACKAGE_IDENTIFIER, componentIdentifier).log();
        } catch (SizeLimitException e) {
            logger.atError().log("Size limit reached", e);
            throw e;
        } catch (PackageLoadingException | PackageDownloadException e) {
            logger.atError().log("Failed to prepare package {}", componentIdentifier, e);
            throw e;
        }
    }

    void prepareArtifacts(ComponentIdentifier componentIdentifier, List<ComponentArtifact> artifacts)
            throws PackageLoadingException, PackageDownloadException, InvalidArtifactUriException,
            InterruptedException {
        if (artifacts == null) {
            logger.atWarn().kv(PACKAGE_IDENTIFIER, componentIdentifier)
                    .log("Artifact list was null, expected non-null and non-empty");
            return;
        }
        Path packageArtifactDirectory = componentStore.resolveArtifactDirectoryPath(componentIdentifier);

        logger.atDebug().setEventType("downloading-package-artifacts")
                .addKeyValue(PACKAGE_IDENTIFIER, componentIdentifier).log();

        for (ComponentArtifact artifact : artifacts) {
            ArtifactDownloader downloader = artifactDownloaderFactory
                    .getArtifactDownloader(componentIdentifier, artifact, packageArtifactDirectory);
            if (downloader.downloadRequired()) {
                Optional<String> errorMsg = downloader.checkDownloadable();
                if (errorMsg.isPresent()) {
                    throw new PackageDownloadException(String.format(
                            "Download required for artifact %s but device configs are invalid: %s",
                            artifact.getArtifactUri(), errorMsg.get()));
                }
                // Check disk size limits before download
                // TODO: [P41215447]: Check artifact size for all artifacts to download early to fail early
                long usableSpaceBytes = componentStore.getUsableSpace();
                if (usableSpaceBytes < DEFAULT_MIN_DISK_AVAIL_BYTES) {
                    throw new SizeLimitException(
                            String.format("Disk space critical: %d bytes usable, %d bytes minimum allowed",
                                    usableSpaceBytes, DEFAULT_MIN_DISK_AVAIL_BYTES));
                }
                if (downloader.checkComponentStoreSize()) {
                    long downloadSize = downloader.getDownloadSize();
                    long storeContentSize = componentStore.getContentSize();
                    if (storeContentSize + downloadSize > getConfiguredMaxSize()) {
                        throw new SizeLimitException(String.format(
                                "Component store size limit reached: %d bytes existing, %d bytes needed"
                                        + ", %d bytes maximum allowed total", storeContentSize, downloadSize,
                                getConfiguredMaxSize()));
                    }
                }
                try {
                    downloader.download();
                } catch (IOException e) {
                    throw new PackageDownloadException(
                            String.format("Failed to download component %s artifact %s", componentIdentifier, artifact),
                            e);
                }
            }
            if (downloader.canSetFilePermissions()) {
                File artifactFile = downloader.getArtifactFile();
                if (artifactFile != null) {
                    try {
                        Permissions.setArtifactPermission(artifactFile.toPath(),
                                artifact.getPermission().toFileSystemPermission());
                    } catch (IOException e) {
                        throw new PackageDownloadException(
                                String.format("Failed to change permissions of component %s artifact %s",
                                        componentIdentifier, artifact), e);
                    }
                }
            }
            if (downloader.canUnarchiveArtifact()) {
                Unarchive unarchive = artifact.getUnarchive();
                if (unarchive == null) {
                    unarchive = Unarchive.NONE;
                }

                File artifactFile = downloader.getArtifactFile();
                if (artifactFile != null && !unarchive.equals(Unarchive.NONE)) {
                    try {
                        Path unarchivePath =
                                nucleusPaths.unarchiveArtifactPath(componentIdentifier, getFileName(artifactFile));
                        unarchiver.unarchive(unarchive, artifactFile, unarchivePath);
                        if (downloader.canSetFilePermissions()) {
                            try {
                                Permissions.setArtifactPermission(unarchivePath,
                                        artifact.getPermission().toFileSystemPermission());
                            } catch (IOException e) {
                                throw new PackageDownloadException(
                                        String.format("Failed to change permissions of component %s artifact %s",
                                                componentIdentifier, artifact), e);
                            }
                        }
                    } catch (IOException e) {
                        throw new PackageDownloadException(
                                String.format("Failed to unarchive component %s artifact %s", componentIdentifier,
                                        artifact), e);
                    }
                }
            }
        }
    }

    private long getConfiguredMaxSize() {
        return Coerce.toLong(deviceConfiguration.getComponentStoreMaxSizeBytes());
    }

    /**
     * Delete stale versions from local store. It's best effort and all the errors are logged.
     */
    public void cleanupStaleVersions() {
        if (platform.getAndroidPackageManager() != null) {
            // should be called before main code of cleanupStaleVersions()
            uninstallStaleAndroidPackages();
        }

        logger.atDebug("cleanup-stale-versions-start").log();
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
                try {
                    ComponentIdentifier identifier = new ComponentIdentifier(compName, new Semver(compVersion));
                    removeRecipeDigestIfExists(identifier);
                    componentStore.deleteComponent(identifier);
                } catch (SemverException | PackageLoadingException e) {
                    // Log a warn here. This shouldn't cause a deployment to fail.
                    logger.atWarn().kv(COMPONENT_NAME, compName).kv("version", compVersion).setCause(e)
                            .log("Failed to clean up component");
                }
            }
        }
        logger.atDebug("cleanup-stale-versions-finish").log();
    }

    /**
     * Uninstall stale APK packages from Android core.
     */
    public void uninstallStaleAndroidPackages() {
        logger.atDebug("uninstall-stale-android-packages-start").log();
        Set<String> androidPackagesToKeep = getAndroidPackagesToKeep();
        Set<String> packagesToRemove = listAndroidPackagesToRemove(androidPackagesToKeep);

        AndroidPackageManager androidPackageManager = platform.getAndroidPackageManager();
        for (String packageToRemove : packagesToRemove) {
            if (androidPackageManager == null) {
                // even if no APK manager we will mark package as uninstalled for testing purposes
                updateAPKInstalled(packageToRemove, false);
            } else {
                Future future = executorService.submit(() -> {
                    try {
                        androidPackageManager.uninstallPackage(packageToRemove, logger);
                        updateAPKInstalled(packageToRemove, false);
                    } catch (IOException | InterruptedException e) {
                        logger.atError().kv(COMPONENT_NAME, packageToRemove).setCause(e)
                                .log("Failed to uninstall Android package");
                    }
                    });
                try {
                    future.get(DEFAULT_ANDROID_PACKAGE_UNINSTALL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    future.cancel(true);
                    logger.atError().kv(COMPONENT_NAME, packageToRemove).setCause(e)
                            .log("Failed when execute uninstall Android package");
                }
            }
        }
    }

    /**
     * Query service config to obtain non-stale android packages which should not be cleaned up.
     *
     *
     * @return Set of component names equals to Android package names
     */
    public Set<String> getAndroidPackagesToKeep() {
        Set<String> result = new HashSet<>();
        for (GreengrassService service : kernel.orderedDependencies()) {
            // Assume all components are Android components due to will run only on Android.
            result.add(service.getName());
        }
        return result;
    }

    /**
     * Get the packages have APK installed.
     * Android specific.
     *
     * @param required packages still required
     * @return a packages which have APK installed.
     */
    public Set<String> listAndroidPackagesToRemove(final Set<String> required) {
        Set<String> result = new HashSet<>();

        Map<String, Set<String>> allComponentVersions = componentStore.listAvailableComponentVersions();
        for (Map.Entry<String, Set<String>> localVersions : allComponentVersions.entrySet()) {
            String componentName = localVersions.getKey();

            // skip below checks if in required list
            if (required.contains(componentName)) {
                continue;
            }

            Set<String> compVersions = localVersions.getValue();
            for (String compVersion : compVersions) {
                ComponentIdentifier identifier = new ComponentIdentifier(componentName, new Semver(compVersion));
                try {
                    RecipeMetadata recipeMetadata = componentStore.getRecipeMetadata(identifier);
                    if (recipeMetadata != null && recipeMetadata.isAPKInstalled()) {
                        result.add(componentName);
                        break;
                    }
                } catch (PackageLoadingException e) {
                    logger.atWarn().kv(COMPONENT_NAME, componentName)
                            .kv("version", compVersion)
                            .setCause(e).log("Failed to get recive metadata");
                }
            }
        }
        return result;
    }

    /**
     * Set APK installed flags in all version of component.
     *
     * @param componentName name of component equals to APK package
     */
    public void setAPKInstalled(String componentName) {
        updateAPKInstalled(componentName, true);
    }

    /**
     * Set/reset APK installed flags in all version of component.
     *
     * @param componentName name of component equals to APK package
     * @param isAPKInstalled new APK installation state
     */
    public void updateAPKInstalled(String componentName, boolean isAPKInstalled) {
        logger.atDebug("update-apk-installed-start").log();

        Requirement req = Requirement.buildNPM("*"); // any version of component
        try {
            List<ComponentIdentifier> componentIdentifiers = componentStore.listAvailableComponent(componentName, req);
            for (ComponentIdentifier componentIdentifier : componentIdentifiers) {
                try {
                    RecipeMetadata recipeMetadata = componentStore.getRecipeMetadata(componentIdentifier);
                    recipeMetadata.setAPKInstalled(isAPKInstalled);
                    componentStore.saveRecipeMetadata(componentIdentifier, recipeMetadata);
                } catch (PackageLoadingException e) {
                    logger.atWarn().kv(COMPONENT_NAME, componentName)
                            .kv("version", Coerce.toString(componentIdentifier.getVersion()))
                            .kv("isAPKInstalled", isAPKInstalled)
                            .setCause(e).log("Couldn't update APK installed flag");
                }
            }
        } catch (PackageLoadingException e) {
            logger.atError().kv(COMPONENT_NAME, componentName)
                    .kv("isAPKInstalled", isAPKInstalled)
                    .setCause(e).log("Failed to update APK installed flag for component");
        }
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
                    .log("Didn't find an active service for this package running in the Nucleus.");
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
            throw new NoAvailableComponentVersionException("No local component version satisfies the requirement.",
                    componentName, requirement);
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
