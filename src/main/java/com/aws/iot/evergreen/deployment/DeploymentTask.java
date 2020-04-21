package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
@AllArgsConstructor
public class DeploymentTask implements Callable<Void> {
    private static final int TIMEOUT_MINUTES = 60;

    private final DependencyResolver dependencyResolver;
    private final PackageStore packageStore;
    private final KernelConfigResolver kernelConfigResolver;
    private final Kernel kernel;
    private final Logger logger;
    private final DeploymentDocument deploymentDocument;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";

    @Override
    public Void call() throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException {
        try {
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue("deploymentId", deploymentDocument.getDeploymentId()).log("Start deployment task");

            // TODO: DA compute list of all root level packages by looking across root level packages
            //  of all groups, when multi group support is added.
            List<String> rootPackages = new ArrayList<>(deploymentDocument.getRootPackages());

            List<PackageIdentifier> desiredPackages = dependencyResolver
                    .resolveDependencies(deploymentDocument, rootPackages);
            packageStore.preparePackages(desiredPackages).get(TIMEOUT_MINUTES, TimeUnit.MINUTES);

            Map<Object, Object> newConfig = kernelConfigResolver.resolve(desiredPackages, deploymentDocument,
                    rootPackages);
            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            kernel.mergeInNewConfig(deploymentDocument.getDeploymentId(), deploymentDocument.getTimestamp(),
                    newConfig).get();
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue("deploymentId", deploymentDocument.getDeploymentId()).log("Finish deployment task");
        // TODO: unwrap ExecutionException to see which one is retryable.
        } catch (PackageVersionConflictException | UnexpectedPackagingException | ExecutionException e) {
            throw new NonRetryableDeploymentTaskFailureException(e);
        } catch (InterruptedException | IOException | PackagingException | TimeoutException e) {
            throw new RetryableDeploymentTaskFailureException(e);
        }
        return null;
    }
}
