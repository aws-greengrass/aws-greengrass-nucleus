/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.greengrassv2data.model.AccessDeniedException;
import software.amazon.awssdk.services.greengrassv2data.model.ConflictException;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;
import software.amazon.awssdk.services.greengrassv2data.model.InternalServerException;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;
import software.amazon.awssdk.services.greengrassv2data.model.ThrottlingException;
import software.amazon.awssdk.services.greengrassv2data.model.ValidationException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ACCESS_DENIED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.BAD_REQUEST;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.CLOUD_API_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.CONFLICTED_REQUEST;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DEPLOYMENT_INTERRUPTED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.INSTALLED_COMPONENT_NOT_FOUND;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_MAPPING_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_WRITE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.NETWORK_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.RESOURCE_NOT_FOUND;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_ACCESS_DENIED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_BAD_REQUEST;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_RESOURCE_NOT_FOUND;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_SERVER_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.SERVER_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.THROTTLING_ERROR;

public final class DeploymentErrorCodeUtils {

    private static final Logger logger = LogManager.getLogger(DeploymentErrorCodeUtils.class);

    private static final List<Class<? extends Exception>> NETWORK_OFFLINE_EXCEPTION =
            Arrays.asList(DeviceConfigurationException.class, SdkClientException.class);

    private DeploymentErrorCodeUtils() {
    }

    /**
     * Walk through exception chain and generate deployment error report.
     *
     * @param e exception passed to DeploymentResult
     * @return error code stack and error types in a pair
     */
    public static Pair<List<String>, List<String>> generateErrorReportFromExceptionStack(Throwable e) {
        // Use a linked hash set to remove duplicates while preserving order
        Set<DeploymentErrorCode> errorCodeSet =
                new LinkedHashSet<>(Collections.singletonList(DeploymentErrorCode.DEPLOYMENT_FAILURE));
        Map<String, DeploymentErrorCode> errorContext = new HashMap<>();
        List<DeploymentErrorType> errorTypesFromException = new ArrayList<>();

        // keep a visited set to avoid infinite loop
        Set<Throwable> visitedExceptionSet = new HashSet<>();

        // iterating through the chain
        Throwable temp = e;
        while (temp != null && !visitedExceptionSet.contains(temp)) {
            translateExceptionToErrorCode(errorCodeSet, temp, errorContext, errorTypesFromException);
            visitedExceptionSet.add(temp);
            temp = temp.getCause();
        }

        List<String> errorStack = errorCodeSet.stream().map(Enum::toString).collect(Collectors.toList());
        // remove duplicate types
        List<String> errorTypes = Stream.concat(errorTypesFromException.stream(),
                        errorCodeSet.stream().map(DeploymentErrorCode::getErrorType)).distinct()
                .filter(type -> !type.equals(DeploymentErrorType.NONE)).map(Enum::toString)
                .collect(Collectors.toList());

        return new Pair<>(errorStack, errorTypes);
    }

    private static void translateExceptionToErrorCode(Set<DeploymentErrorCode> errorCodeSet, Throwable e,
                                                      Map<String, DeploymentErrorCode> errorContext,
                                                      List<DeploymentErrorType> errorTypeList) {
        if (e instanceof DeploymentException) {
            errorContext.putAll(((DeploymentException) e).getErrorContext());
            errorCodeSet.addAll(((DeploymentException) e).getErrorCodes());
            errorTypeList.addAll(((DeploymentException) e).getErrorTypes());
        }
        if (e instanceof IOException) {
            collectErrorCodesFromIOException(errorCodeSet, (IOException) e);
        } else if (e instanceof GreengrassV2DataException) {
            collectErrorCodesFromGreengrassV2DataException(errorCodeSet, (GreengrassV2DataException) e);
        } else if (e instanceof S3Exception) {
            collectErrorCodesFromS3Exception(errorCodeSet, (S3Exception) e);
        } else if (e instanceof ServiceLoadException) {
            collectErrorCodesFromServiceLoadException(errorCodeSet);
        } else if (NETWORK_OFFLINE_EXCEPTION.stream().anyMatch(c -> c.isInstance(e))) {
            errorCodeSet.add(NETWORK_ERROR);
        } else if (e instanceof InterruptedException) {
            errorCodeSet.add(DEPLOYMENT_INTERRUPTED);
        }
        if (errorContext.containsKey(e.getClass().getSimpleName())) {
            errorCodeSet.add(errorContext.get(e.getClass().getSimpleName()));
        }
    }

    private static void collectErrorCodesFromIOException(Set<DeploymentErrorCode> errorCodeSet, IOException e) {
        errorCodeSet.add(IO_ERROR);
        if (e instanceof JsonMappingException || e instanceof JsonParseException) {
            errorCodeSet.add(IO_MAPPING_ERROR);
        } else if (e instanceof JsonProcessingException) {
            // JsonProcessingException is parent class of JsonMappingException and JsonParseException
            errorCodeSet.add(IO_WRITE_ERROR);
        }
    }

    private static void collectErrorCodesFromGreengrassV2DataException(Set<DeploymentErrorCode> errorCodeSet,
                                                                       GreengrassV2DataException e) {
        errorCodeSet.add(CLOUD_API_ERROR);
        if (e instanceof ResourceNotFoundException) {
            errorCodeSet.add(RESOURCE_NOT_FOUND);
        } else if (e instanceof AccessDeniedException) {
            errorCodeSet.add(ACCESS_DENIED);
        } else if (e instanceof ValidationException) {
            errorCodeSet.add(BAD_REQUEST);
        } else if (e instanceof ThrottlingException) {
            errorCodeSet.add(THROTTLING_ERROR);
        } else if (e instanceof ConflictException) {
            errorCodeSet.add(CONFLICTED_REQUEST);
        } else if (e instanceof InternalServerException) {
            errorCodeSet.add(SERVER_ERROR);
        }
    }


    private static void collectErrorCodesFromS3Exception(Set<DeploymentErrorCode> errorCodeSet, S3Exception e) {
        errorCodeSet.add(S3_ERROR);
        int s3StatusCode = e.statusCode();
        if (s3StatusCode == HttpStatusCode.NOT_FOUND) {
            errorCodeSet.add(S3_RESOURCE_NOT_FOUND);
        } else if (s3StatusCode == HttpStatusCode.FORBIDDEN) {
            errorCodeSet.add(S3_ACCESS_DENIED);
        } else if (s3StatusCode >= HttpStatusCode.BAD_REQUEST && s3StatusCode < HttpStatusCode.INTERNAL_SERVER_ERROR) {
            errorCodeSet.add(S3_BAD_REQUEST);
        } else if (s3StatusCode >= HttpStatusCode.INTERNAL_SERVER_ERROR) {
            errorCodeSet.add(S3_SERVER_ERROR);
        }
    }

    private static void collectErrorCodesFromServiceLoadException(Set<DeploymentErrorCode> errorCodeSet) {
        errorCodeSet.add(INSTALLED_COMPONENT_NOT_FOUND);
    }

    /**
     * Assign error type to deployment request errors.
     *
     * @param deploymentType deployment type
     * @return nucleus error if local deployment; cloud service error if cloud deployment
     */
    public static DeploymentErrorType getDeploymentRequestErrorType(Deployment.DeploymentType deploymentType) {
        switch (deploymentType) {
            // if a local deployment request is invalid, then it's a bug in CLI and mark it as Nucleus error
            case LOCAL:
                return DeploymentErrorType.NUCLEUS_ERROR;
            // if cloud deployment, then mark it cloud service error
            case SHADOW:
            case IOT_JOBS:
                return DeploymentErrorType.CLOUD_SERVICE_ERROR;
            default:
                return DeploymentErrorType.UNKNOWN_ERROR;
        }
    }

    /**
     * Check whether a service is 1p.
     *
     * @param serviceName    service to be checked
     * @param kernel         a reference of kernel
     * @return AWS component error if account is AWS; user component error if a customer account; a generic component
     *         error type if anything wrong happened.
     */
    public static DeploymentErrorType classifyComponentError(String serviceName, Kernel kernel) {
        GreengrassService service;
        try {
            service = kernel.locate(serviceName);
        } catch (ServiceLoadException e) {
            logger.atWarn().log("Failed to locate component while classifying component error");
            return DeploymentErrorType.COMPONENT_ERROR;
        }
        return service == null ? DeploymentErrorType.COMPONENT_ERROR : classifyComponentError(service, kernel);
    }

    /**
     * Check whether a service is 1p.
     *
     * @param service        service to be checked
     * @param kernel         a reference of kernel
     * @return AWS component error if account is AWS; user component error if a customer account; a generic component
     *         error type if anything wrong happened.
     */
    public static DeploymentErrorType classifyComponentError(GreengrassService service, Kernel kernel) {
        // get service topic for name and version
        Topics serviceTopics = service.getServiceConfig();
        if (serviceTopics == null) {
            logger.atWarn().log("Null service topic while classifying component error");
            return DeploymentErrorType.COMPONENT_ERROR;
        }

        // load component arn from recipe metadata json on disk
        String arnString;
        try {
            ComponentStore componentStore = kernel.getContext().get(ComponentStore.class);
            arnString = componentStore.getRecipeMetadata(ComponentIdentifier.fromServiceTopics(serviceTopics))
                    .getComponentVersionArn();
        } catch (PackageLoadingException e) {
            logger.atDebug().log("Failed to load component metadata file from disk while classifying component error."
                    + "Either the component is locally installed or the metadata file is corrupted");
            DeploymentService deploymentService = null;
            try {
                GreengrassService deploymentServiceLocate = kernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
                if (deploymentServiceLocate instanceof DeploymentService) {
                    deploymentService = (DeploymentService) deploymentServiceLocate;
                    Set<String> groups = deploymentService.getGroupNamesForUserComponent(service.getName());
                    if (groups.contains(LOCAL_DEPLOYMENT_GROUP_NAME)) {
                        return DeploymentErrorType.USER_COMPONENT_ERROR;
                    }
                }
                logger.atWarn().log("Failed to load component metadata file from disk while classifying component "
                        + "error. Component metadata file possibly corrupted");
                return DeploymentErrorType.COMPONENT_ERROR;
            } catch (ServiceLoadException e2) {
                logger.atWarn().cause(e2).log("Unable to locate {} service while classifying component error",
                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
            }
            return DeploymentErrorType.COMPONENT_ERROR;
        }

        // parse the arn to check if account id is AWS
        try {
            Arn arn = Arn.fromString(arnString);

            if (!arn.accountId().isPresent()) {
                logger.atWarn().log("Failed to parse account id in component arn while classifying component error");
                return DeploymentErrorType.COMPONENT_ERROR;
            }

            if ("aws".equals(arn.accountId().get())) {
                return DeploymentErrorType.AWS_COMPONENT_ERROR;
            } else {
                return DeploymentErrorType.USER_COMPONENT_ERROR;
            }
        } catch (IllegalArgumentException e) {
            // an invalid component arn
            logger.atWarn().setCause(e).log("Failed to parse component arn while classifying component error");
            return DeploymentErrorType.COMPONENT_ERROR;
        }
    }
}
