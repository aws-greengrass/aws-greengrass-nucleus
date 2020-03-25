package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.InvalidRequestException;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
@AllArgsConstructor
public class DeploymentTask implements Callable<Void> {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DependencyResolver dependencyResolver;
    private final PackageCache packageCache;
    private final KernelConfigResolver kernelConfigResolver;
    private final Kernel kernel;
    private final Logger logger;
    private final Map<String, Object> jobDocument;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";

    @Override
    public Void call() throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException {
        try {
            DeploymentDocument document = parseAndValidateJobDocument(jobDocument);
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue("deploymentId", document.getDeploymentId()).log("Start deployment task");
            List<PackageIdentifier> desiredPackages = dependencyResolver.resolveDependencies(document);
            // Block this without timeout because a device can be offline and it can take quite a long time
            // to download a package.
            packageCache.preparePackages(desiredPackages).get();
            // TODO : Compute the set of packages to be removed from the fleet - package information
            // and pass it to the config resolver
            Map<Object, Object> newConfig = kernelConfigResolver.resolve(desiredPackages, document, new HashSet<>());
            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            kernel.mergeInNewConfig(document.getDeploymentId(), document.getTimestamp(), newConfig).get();
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .addKeyValue("deploymentId", document.getDeploymentId()).log("Finish deployment task");
        } catch (PackageVersionConflictException | UnexpectedPackagingException | InvalidRequestException e) {
            throw new NonRetryableDeploymentTaskFailureException(e);
        } catch (ExecutionException | InterruptedException | IOException | PackagingException e) {
            throw new RetryableDeploymentTaskFailureException(e);
        }
        return null;
    }

    protected DeploymentDocument parseAndValidateJobDocument(Map<String, Object> jobDocument)
            throws InvalidRequestException {

        if (jobDocument == null || jobDocument.isEmpty()) {
            throw new InvalidRequestException("Job document cannot be empty");
        }

        try {
            DeploymentDocument deploymentDocument = null;
            String jobDocumentString = OBJECT_MAPPER.writeValueAsString(jobDocument);
            deploymentDocument = OBJECT_MAPPER.readValue(jobDocumentString, DeploymentDocument.class);
            return deploymentDocument;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Unable to parse the job document", e);
        }
    }
}
