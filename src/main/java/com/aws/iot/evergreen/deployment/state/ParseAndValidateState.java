/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentContext;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.NameVersionPair;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ParseAndValidateState extends BaseState {

    /**
     * Constructor for ParseAndValidateState.
     *
     * @param deploymentContext The deployment packet containing the deployment context
     * @param objectMapper      Object Mapper {@link ObjectMapper}
     * @param logger            Logger {@link Logger}
     */
    public ParseAndValidateState(DeploymentContext deploymentContext, ObjectMapper objectMapper, Logger logger) {
        super(deploymentContext, objectMapper, logger);
    }

    @Override
    public boolean canProceed() {
        return true;
    }

    @Override
    public void proceed() throws DeploymentFailureException {
        logger.info("Parsing and validating the job document");
        DeploymentDocument deploymentDocument = parseAndValidateJobDocument(deploymentContext.getJobDocument());
        logger.atInfo().log("Deployment configuration received in the job is {}", deploymentDocument.toString());
        deploymentContext.setDeploymentId(deploymentDocument.getDeploymentId());
        deploymentContext.setDeploymentCreationTimestamp(deploymentDocument.getTimestamp());
        Set<PackageMetadata> proposedPackages = getPackageMetadata(deploymentDocument);
        deploymentContext.setProposedPackagesFromDeployment(proposedPackages);
        //cleaning up the space since this is no longer needed in the process
        deploymentContext.getJobDocument().clear();
    }

    //Unnecessary?
    private Set<PackageMetadata> getPackageMetadata(DeploymentDocument deploymentDocument) {
        logger.info("Getting package metadata");
        Map<String, DeploymentPackageConfiguration> nameToPackageConfig =
                deploymentDocument.getDeploymentPackageConfigurationList().stream()
                        .collect(Collectors.toMap(pkgConfig -> pkgConfig.getPackageName(), pkgConfig -> pkgConfig));
        Map<String, PackageMetadata> nameToPackageMetadata = new HashMap<>();
        nameToPackageConfig.forEach((pkgName, configuration) -> {
            nameToPackageMetadata.put(pkgName,
                    new PackageMetadata(configuration.getPackageName(), configuration.getResolvedVersion(),
                            configuration.getVersionConstraint(), new HashSet<>(), configuration.getParameters()));
        });
        Set<PackageMetadata> packageMetadata = new HashSet<>();
        for (Map.Entry<String, PackageMetadata> entry : nameToPackageMetadata.entrySet()) {
            //Go through all the package metadata and create their dependency trees
            String packageName = entry.getKey();
            PackageMetadata currentPackageMetdata = entry.getValue();
            if (nameToPackageConfig.containsKey(packageName)) {
                DeploymentPackageConfiguration deploymentPackageConfiguration = nameToPackageConfig.get(packageName);
                if (deploymentPackageConfiguration.getListOfDependentPackages() != null) {
                    for (NameVersionPair dependencyNameVersion : deploymentPackageConfiguration
                            .getListOfDependentPackages()) {
                        if (nameToPackageMetadata.containsKey(dependencyNameVersion.getPackageName())) {
                            currentPackageMetdata.getDependsOn()
                                    .add(nameToPackageMetadata.get(dependencyNameVersion.getPackageName()));
                        }
                        //TODO: Handle case when package does not exist for a specified dependency
                    }
                }
            }
            if (deploymentDocument.getListOfPackagesToDeploy().contains(packageName)) {
                packageMetadata.add(currentPackageMetdata);
            }
        }

        logger.atInfo().log("Set of package metadata derived is {}", packageMetadata.toString());
        return packageMetadata;
    }

    private DeploymentDocument parseAndValidateJobDocument(HashMap<String, Object> jobDocument)
            throws DeploymentFailureException {
        if (jobDocument == null) {
            String errorMessage = "Job document cannot be empty";
            throw new DeploymentFailureException(errorMessage);
        }
        DeploymentDocument deploymentDocument = null;
        try {
            String jobDocumentString = objectMapper.writeValueAsString(jobDocument);
            deploymentDocument = objectMapper.readValue(jobDocumentString, DeploymentDocument.class);
            return deploymentDocument;
        } catch (JsonProcessingException e) {
            String errorMessage = "Unable to parse the job document";
            logger.error(errorMessage, e);
            logger.error(e.getMessage());
            throw new DeploymentFailureException(errorMessage, e);
        }

    }

    @Override
    public void cancel() {

    }
}
