/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.LoaderLogsSummarizer;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;

public class KernelUpdateDeploymentTask implements DeploymentTask {
    public static final String RESTART_PANIC_FILE_NAME = "restart_panic";
    private final Kernel kernel;
    private final Logger logger;
    private final Deployment deployment;
    private final ComponentManager componentManager;
    private final CompletableFuture<DeploymentResult> deploymentResultCompletableFuture;
    private final Path loaderLogsPath;

    /**
     * Constructor for DefaultDeploymentTask.
     *
     * @param kernel           Kernel instance
     * @param logger           Logger instance
     * @param deployment       Deployment instance
     * @param componentManager ComponentManager instance
     */
    public KernelUpdateDeploymentTask(Kernel kernel, Logger logger, Deployment deployment,
                                      ComponentManager componentManager) {
        this.kernel = kernel;
        this.deployment = deployment;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getGreengrassDeploymentId());
        this.componentManager = componentManager;
        this.deploymentResultCompletableFuture = new CompletableFuture<>();
        this.loaderLogsPath = kernel.getNucleusPaths().loaderLogsPath();
    }

    @SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
    @Override
    public DeploymentResult call() {
        kernel.getContext().get(ExecutorService.class).execute(this::waitForServicesToStart);
        DeploymentResult result;
        try {
            result = deploymentResultCompletableFuture.get();
        } catch (InterruptedException | ExecutionException | CancellationException e) {
            // nothing to report when deployment is cancelled
            return null;
        }
        componentManager.cleanupStaleVersions();
        return result;

    }

    private void waitForServicesToStart() {
        Deployment.DeploymentStage stage = deployment.getDeploymentStage();
        DeploymentResult result = null;
        try {
            Set<GreengrassService> servicesToTrack = kernel.findAutoStartableServicesToTrack();
            long mergeTimestamp = kernel.getConfig().lookup("system", "rootpath").getModtime();

            logger.atInfo().kv("serviceToTrack", servicesToTrack).kv("mergeTime", mergeTimestamp)
                    .log("Nucleus update workflow waiting for services to complete update");
            DeploymentConfigMerger.waitForServicesToStart(servicesToTrack, mergeTimestamp, kernel,
                    deploymentResultCompletableFuture);
            if (deploymentResultCompletableFuture.isCancelled()) {
                logger.atDebug().log("Kernel update deployment is cancelled");
            } else if (KERNEL_ACTIVATION.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE,
                        getDeploymentStatusDetails());
            } else if (ROLLBACK_BOOTSTRAP.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                        getDeploymentStatusDetails());
            }
        } catch (InterruptedException e) {
            if (!deploymentResultCompletableFuture.isCancelled()) {
                logger.atError("deployment-interrupted", e).log();
                try {
                    saveDeploymentStatusDetails(e);
                } catch (IOException ioException) {
                    logger.atError().log("Failed to persist deployment error information", ioException);
                }
                // Interrupted workflow. Shutdown kernel and retry this stage.
                kernel.shutdown(30, REQUEST_RESTART);
            }
        } catch (ServiceUpdateException e) {
            logger.atError("deployment-errored", e).log();
            if (KERNEL_ACTIVATION.equals(stage)) {
                try {
                    KernelAlternatives kernelAlternatives = kernel.getContext().get(KernelAlternatives.class);
                    final boolean bootstrapOnRollbackRequired = kernelAlternatives.prepareBootstrapOnRollbackIfNeeded(
                            kernel.getContext(), kernel.getContext().get(DeploymentDirectoryManager.class),
                            kernel.getContext().get(BootstrapManager.class));
                    deployment.setDeploymentStage(bootstrapOnRollbackRequired ? ROLLBACK_BOOTSTRAP : KERNEL_ROLLBACK);
                    saveDeploymentStatusDetails(e);
                    // Rollback workflow. Flip symlinks and restart kernel
                    kernelAlternatives.prepareRollback();
                    kernel.shutdown(30, REQUEST_RESTART);
                } catch (IOException ioException) {
                    logger.atError().log("Failed to set up Nucleus rollback directory", ioException);
                    result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, e);
                }
            } else if (KERNEL_ROLLBACK.equals(stage) || ROLLBACK_BOOTSTRAP.equals(stage)) {
                logger.atError().log("Nucleus update workflow failed on rollback", e);
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                        getDeploymentStatusDetails());
            }
        }

        deploymentResultCompletableFuture.complete(result);
    }

    private void saveDeploymentStatusDetails(Throwable failureCause) throws IOException {
        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(failureCause);
        deployment.setErrorStack(errorReport.getLeft());
        deployment.setErrorTypes(errorReport.getRight());
        deployment.setStageDetails(Utils.generateFailureMessage(failureCause));
        kernel.getContext().get(DeploymentDirectoryManager.class).writeDeploymentMetadata(deployment);
    }

    private DeploymentException getDeploymentStatusDetails() {
        if (Utils.isEmpty(deployment.getStageDetails())) {
            try {
                if (Files.deleteIfExists(
                        kernel.getNucleusPaths().workPath(DEFAULT_NUCLEUS_COMPONENT_NAME)
                                .resolve(RESTART_PANIC_FILE_NAME).toAbsolutePath())) {
                    String loaderLogs;
                    try {
                        loaderLogs = new String(Files.readAllBytes(this.loaderLogsPath), StandardCharsets.UTF_8);
                        return new DeploymentException(
                            String.format("Nucleus update workflow failed to restart Nucleus.%n%s",
                                    LoaderLogsSummarizer.summarizeLogs(loaderLogs)),
                            DeploymentErrorCode.NUCLEUS_RESTART_FAILURE);
                    } catch (IOException e) {
                        logger.atWarn().log("Unable to read Nucleus logs for restart failure", e);
                        return new DeploymentException(
                            "Nucleus update workflow failed to restart Nucleus. Please look at the device and loader "
                                    + "logs for more info.",
                            DeploymentErrorCode.NUCLEUS_RESTART_FAILURE);
                    }
                } else {
                    return new DeploymentException("Nucleus update workflow failed to restart Nucleus due to an "
                            + "unexpected device IO error",
                            DeploymentErrorCode.IO_WRITE_ERROR);
                }
            } catch (IOException e) {
                return new DeploymentException("Nucleus update workflow failed to restart Nucleus due to an "
                        + "unexpected device IO error. See loader logs for more details", e,
                        DeploymentErrorCode.IO_WRITE_ERROR);
            }
        }
        
        List<DeploymentErrorCode> errorStack = deployment.getErrorStack() == null ? Collections.emptyList()
                : deployment.getErrorStack().stream().map(DeploymentErrorCode::valueOf).collect(Collectors.toList());

        List<DeploymentErrorType> errorTypes = deployment.getErrorTypes() == null ? Collections.emptyList()
                : deployment.getErrorTypes().stream().map(DeploymentErrorType::valueOf).collect(Collectors.toList());

        return new DeploymentException(deployment.getStageDetails(), errorStack, errorTypes);
    }

    @Override
    public void cancel() {
        deploymentResultCompletableFuture.cancel(false);
    }
}
