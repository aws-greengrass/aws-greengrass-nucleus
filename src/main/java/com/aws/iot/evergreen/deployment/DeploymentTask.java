package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
public class DeploymentTask implements Callable<Void> {
    private final DependencyResolver dependencyResolver;
    private final PackageCache packageCache;
    private final KernelConfigResolver kernelConfigResolver;
    private final Kernel kernel;
    private final Logger logger;
    private final DeploymentDocument document;

    /**
     * DeploymentTask Constructor.
     * @param dependencyResolver dependency resolver
     * @param packageCache package cache
     * @param kernelConfigResolver kernel config resolver
     * @param kernel kernel
     * @param logger logger
     * @param document deployment document
     */
    public DeploymentTask(DependencyResolver dependencyResolver, PackageCache packageCache,
                          KernelConfigResolver kernelConfigResolver, Kernel kernel,
                          Logger logger, DeploymentDocument document) {
        this.dependencyResolver = dependencyResolver;
        this.packageCache = packageCache;
        this.kernelConfigResolver = kernelConfigResolver;
        this.kernel = kernel;
        this.logger = logger;
        this.document = document;
    }

    @Override
    public Void call() throws DeploymentTaskFailureException {
        try {
            logger.atInfo().addKeyValue("deploymentId", document.getDeploymentId())
                    .log("Start deployment task");
            List<PackageIdentifier> desiredPackages = dependencyResolver.resolveDependencies(document);
            // Block this without timeout because a device can be offline and it can take quite a long time
            // to download a package.
            packageCache.preparePackages(desiredPackages).get();
            Map<Object, Object> newConfig = kernelConfigResolver.resolve(desiredPackages, document);
            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            kernel.mergeInNewConfig(document.getDeploymentId(), document.getTimestamp(), newConfig).get();
            logger.atInfo().addKeyValue("deploymentId", document.getDeploymentId())
                    .log("Finish deployment task");
        } catch (PackageVersionConflictException | ExecutionException | InterruptedException e) {
            throw new DeploymentTaskFailureException(e);
        }
        return null;
    }
}
