package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentTask;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
@AllArgsConstructor
public class DefaultDeploymentTask implements DeploymentTask {
    private static final String DEPLOYMENT_ID_LOGGING_KEY = "deploymentId";
    private final DependencyResolver dependencyResolver;
    private final PackageManager packageManager;
    private final KernelConfigResolver kernelConfigResolver;
    private final DeploymentConfigMerger deploymentConfigMerger;
    private final Logger logger;
    @Getter
    private final DeploymentDocument deploymentDocument;
    private final Topics deploymentServiceConfig;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";

    @Override
    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.PrematureDeclaration"})
    public DeploymentResult call()
            throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException {
        Future<Void> preparePackagesFuture = null;
        Future<DeploymentResult> deploymentMergeFuture = null;
        try {
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue(DEPLOYMENT_ID_LOGGING_KEY, deploymentDocument.getDeploymentId())
                    .kv("Deployment service config", deploymentServiceConfig.toPOJO().toString())
                    .log("Starting deployment task");

            Set<String> rootPackages = new HashSet<>(deploymentDocument.getRootPackages());

            Topics groupsToRootPackages =
                    deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
            groupsToRootPackages.iterator().forEachRemaining(node -> {
                Topics groupTopics = (Topics) node;
                if (!groupTopics.getName().equals(deploymentDocument.getGroupName())) {
                    groupTopics.forEach(pkgTopic -> {
                        rootPackages.add(pkgTopic.getName());
                    });
                }
            });

            List<PackageIdentifier> desiredPackages =
                    dependencyResolver.resolveDependencies(deploymentDocument, groupsToRootPackages);

            // Block this without timeout because a device can be offline and it can take quite a long time
            // to download a package.
            preparePackagesFuture = packageManager.preparePackages(desiredPackages);
            preparePackagesFuture.get();

            Map<Object, Object> newConfig =
                    kernelConfigResolver.resolve(desiredPackages, deploymentDocument, new ArrayList<>(rootPackages));
            if (Thread.currentThread().isInterrupted()) {
                logger.atInfo().addKeyValue(DEPLOYMENT_ID_LOGGING_KEY, deploymentDocument.getDeploymentId())
                        .log("Received interrupt before attempting deployment merge, skipping merge");
                return null;
            }
            deploymentMergeFuture = deploymentConfigMerger.mergeInNewConfig(deploymentDocument, newConfig);

            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            DeploymentResult result = deploymentMergeFuture.get();

            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue(DEPLOYMENT_ID_LOGGING_KEY, deploymentDocument.getDeploymentId())
                    .log("Finished deployment task");
            return result;
        } catch (PackageVersionConflictException | UnexpectedPackagingException e) {
            throw new NonRetryableDeploymentTaskFailureException(e);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof PackagingException || t instanceof InterruptedException || t instanceof IOException) {
                throw new RetryableDeploymentTaskFailureException(t);
            }
            throw new NonRetryableDeploymentTaskFailureException(t);
        } catch (InterruptedException e) {
            // DeploymentTask got interrupted while waiting or blocked on either prepare packages
            // or deployment merge step and landed here
            handleCancellation(preparePackagesFuture, deploymentMergeFuture);
            return null;
        } catch (IOException | PackagingException e) {
            throw new RetryableDeploymentTaskFailureException(e);
        }
    }

    /*
     * Handle deployment cancellation
     */
    private void handleCancellation(Future<Void> preparePackagesFuture,
                                    Future<DeploymentResult> deploymentMergeFuture) {
        // Stop downloading packages since the task was cancelled
        if (preparePackagesFuture != null && !preparePackagesFuture.isDone()) {
            preparePackagesFuture.cancel(true);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE)
                    .kv(DEPLOYMENT_ID_LOGGING_KEY, deploymentDocument.getDeploymentId())
                    .log("Cancelled package download due to received interrupt");
            return;
        }
        // Cancel deployment config merge future
        if (deploymentMergeFuture != null && !deploymentMergeFuture.isDone()) {
            deploymentMergeFuture.cancel(false);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE)
                    .kv(DEPLOYMENT_ID_LOGGING_KEY, deploymentDocument.getDeploymentId())
                    .log("Cancelled deployment merge future due to interrupt, update may not get cancelled if"
                            + " it is already being applied");
        }
    }
}
