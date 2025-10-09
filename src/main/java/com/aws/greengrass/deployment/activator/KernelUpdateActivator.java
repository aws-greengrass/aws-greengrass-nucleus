/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.lifecyclemanager.exceptions.DirectoryValidationException;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.KernelUpdateDeploymentTask.RESTART_PANIC_FILE_NAME;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.KERNEL_BIN_DIR;

/**
 * Activation and rollback of Kernel update deployments.
 */
public class KernelUpdateActivator extends DeploymentActivator {
    private final BootstrapManager bootstrapManager;
    private final KernelAlternatives kernelAlternatives;
    private final NucleusPaths nucleusPaths;

    /**
     * Constructor of KernelUpdateActivator.
     *
     * @param kernel Kernel instance
     * @param bootstrapManager BootstrapManager instance
     */
    @Inject
    public KernelUpdateActivator(Kernel kernel, BootstrapManager bootstrapManager) {
        super(kernel);
        this.bootstrapManager = bootstrapManager;
        this.kernelAlternatives = kernel.getContext().get(KernelAlternatives.class);
        this.nucleusPaths = kernel.getNucleusPaths();
    }

    @Override
    public void activate(Map<String, Object> newConfig, Deployment deployment, long configMergeTimestamp,
            CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        if (!takeConfigSnapshot(totallyCompleteFuture)) {
            return;
        }
        try {
            kernelAlternatives.validateLaunchDirSetupVerbose();
        } catch (DirectoryValidationException e) {
            if (!canRecoverMissingLaunchDirSetup()) {
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                                new DeploymentException(
                                        "Unable to process deployment. Greengrass launch directory"
                                                + " is not set up or Greengrass is not set up as a system service",
                                        e)));
                return;
            }

            try {
                kernelAlternatives.validateLoaderAsExecutable();
            } catch (DeploymentException ex) {
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
                return;
            }
        } catch (DeploymentException e) {
            totallyCompleteFuture
                    .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return;
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        KernelLifecycle lifecycle = kernel.getContext().get(KernelLifecycle.class);
        // Preserve tlog state before launch directory is updated to reflect ongoing deployment.
        // Wait for all services to close.
        lifecycle.softShutdown(30);

        updateConfiguration(configMergeTimestamp, newConfig);

        // Try and delete restart panic file if it exists
        try {
            Files.deleteIfExists(nucleusPaths.workPath(DEFAULT_NUCLEUS_COMPONENT_NAME)
                    .resolve(RESTART_PANIC_FILE_NAME)
                    .toAbsolutePath());
        } catch (IOException e) {
            logger.atWarn().log("Unable to delete an existing restart panic file", e);
        }

        Path bootstrapTaskFilePath;
        try {
            bootstrapTaskFilePath = deploymentDirectoryManager.getBootstrapTaskFilePath();
            deploymentDirectoryManager.takeConfigSnapshot(deploymentDirectoryManager.getTargetConfigFilePath());
            bootstrapManager.persistBootstrapTaskList(bootstrapTaskFilePath);
            kernelAlternatives.prepareBootstrap(deploymentDocument.getDeploymentId());
        } catch (IOException e) {
            // TODO: better handling of error codes for different IO operations
            rollback(deployment, e);
            return;
        }

        try {
            int exitCode = bootstrapManager.executeAllBootstrapTasksSequentially(bootstrapTaskFilePath);
            if (!bootstrapManager.hasNext()) {
                logger.atInfo().log("Completed all bootstrap tasks. Continue to activate deployment changes");
            }
            // If exitCode is 0, which happens when all bootstrap tasks are completed, restart in new launch
            // directories and verify handover is complete. As a result, exit code 0 is treated as 100 here.
            logger.atInfo()
                    .log((exitCode == REQUEST_REBOOT ? "device reboot" : "Nucleus restart")
                            + " requested to complete bootstrap task");

            kernel.shutdown(30, exitCode == REQUEST_REBOOT ? REQUEST_REBOOT : REQUEST_RESTART);
        } catch (ServiceUpdateException | IOException e) {
            rollback(deployment, e);
        }
    }

    void rollback(Deployment deployment, Throwable failureCause) {
        logger.atInfo(MERGE_CONFIG_EVENT_KEY, failureCause)
                .kv(DEPLOYMENT_ID_LOG_KEY, deployment.getGreengrassDeploymentId())
                .log("Rolling back failed deployment");

        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(failureCause);
        deployment.setErrorStack(errorReport.getLeft());
        deployment.setErrorTypes(errorReport.getRight());
        deployment.setStageDetails(Utils.generateFailureMessage(failureCause));

        final boolean bootstrapOnRollbackRequired = kernelAlternatives
                .prepareBootstrapOnRollbackIfNeeded(kernel.getContext(), deploymentDirectoryManager, bootstrapManager);

        deployment.setDeploymentStage(bootstrapOnRollbackRequired ? ROLLBACK_BOOTSTRAP : KERNEL_ROLLBACK);

        try {
            deploymentDirectoryManager.writeDeploymentMetadata(deployment);
        } catch (IOException ioException) {
            logger.atError().setCause(ioException).log("Failed to persist deployment details");
        }
        try {
            kernelAlternatives.prepareRollback();
        } catch (IOException e) {
            logger.atError().setCause(e).log("Failed to set up rollback directory");
        }
        // Restart Kernel regardless and rely on loader orchestration
        kernel.shutdown(30, REQUEST_RESTART);
    }

    protected boolean canRecoverMissingLaunchDirSetup() {
        /*
         * Try and relink launch dir with the following replacement criteria 1. check if current Nucleus execution
         * package is valid 2. un-archive current Nucleus version from component store 3. fail with
         * DirectoryValidationException if above steps do not satisfy
         */
        try {
            Path currentNucleusExecutablePath = KernelAlternatives.locateCurrentKernelUnpackDir();
            if (Files.exists(currentNucleusExecutablePath.resolve(KERNEL_BIN_DIR)
                    .resolve(Platform.getInstance().loaderFilename()))) {
                logger.atDebug()
                        .kv("path", currentNucleusExecutablePath)
                        .log("Current Nucleus executable is valid, setting up launch dir");
                kernelAlternatives.relinkInitLaunchDir(currentNucleusExecutablePath, true);
                return true;
            }

            ComponentManager componentManager = kernel.getContext().get(ComponentManager.class);
            List<Path> localNucleusExecutablePaths = componentManager.unArchiveCurrentNucleusVersionArtifacts();
            if (!localNucleusExecutablePaths.isEmpty()) {
                Optional<Path> validNucleusExecutablePath = localNucleusExecutablePaths.stream()
                        .filter(path -> Files
                                .exists(path.resolve(KERNEL_BIN_DIR).resolve(Platform.getInstance().loaderFilename())))
                        .findFirst();
                if (validNucleusExecutablePath.isPresent()) {
                    logger.atDebug()
                            .kv("path", validNucleusExecutablePath.get())
                            .log("Un-archived current Nucleus artifact");
                    kernelAlternatives.relinkInitLaunchDir(validNucleusExecutablePath.get(), true);
                    return true;
                }
            }
            logger.atInfo().log("Cannot recover missing launch dir setup as no local Nucleus artifact is present");
            return false;
        } catch (IOException | URISyntaxException | PackageLoadingException e) {
            logger.atWarn().setCause(e).log("Could not recover missing launch dir setup");
            return false;
        }
    }
}
