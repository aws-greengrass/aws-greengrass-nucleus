package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class DeploymentTask implements Callable<Void> {
    private final DependencyResolver dependencyResolver;
    private final PackageCache packageCache;
    private final KernelConfigResolver kernelConfigResolver;
    private final Kernel kernel;
    private final DeploymentDocument document;

    public DeploymentTask(DependencyResolver dependencyResolver, PackageCache packageCache,
                          KernelConfigResolver kernelConfigResolver, Kernel kernel, DeploymentDocument document) {
        this.dependencyResolver = dependencyResolver;
        this.packageCache = packageCache;
        this.kernelConfigResolver = kernelConfigResolver;
        this.kernel = kernel;
        this.document = document;
    }


    @Override
    public Void call() throws Exception {
        List<PackageIdentifier> desiredPackages = dependencyResolver.resolveDependencies(document);
        packageCache.preparePackages(desiredPackages).get();
        Map<Object, Object> newConfig = kernelConfigResolver.resolve(desiredPackages, document);
        kernel.mergeInNewConfig(document.getDeploymentId(), document.getTimestamp(), newConfig);
        return null;
    }
}
