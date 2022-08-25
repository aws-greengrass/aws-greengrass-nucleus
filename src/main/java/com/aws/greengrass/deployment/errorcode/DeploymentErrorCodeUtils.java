/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.NonNull;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ACCESS_DENIED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.BAD_REQUEST;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.CLOUD_SERVICE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.CONFLICTED_REQUEST;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DEPLOYMENT_INTERRUPTED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_MAPPING_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_WRITE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.LOCATE_INSTALLED_COMPONENT_ERROR;
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

    private static final List<Class<? extends Exception>> NETWORK_OFFLINE_EXCEPTION =
            Arrays.asList(DeviceConfigurationException.class, SdkClientException.class);

    private static final String COMPONENT_ARN_TEMPLATE = "arn:%s:greengrass:%s:%s:components:%s";
    private static final String PARTITION_PATTERN_STRING = "(aws(-[a-z]+)*)";
    private static final String REGION_PATTERN_STRING = "([a-z]{2}(-[a-z]+)+-\\d{1})";
    private static final String COMPONENT_ACCOUNT_ID_PATTERN_STRING = "(aws|\\d{12})";
    private static final String COMPONENT_NAME_WITH_OR_WITHOUT_VERSION_PATTERN_STRING =
            "([a-zA-Z0-9-_\\.]+)" + "(:versions:([^:/\\\\]+))?";
    private static final String COMPONENT_ARN_PATTERN_STRING =
            String.format(COMPONENT_ARN_TEMPLATE, PARTITION_PATTERN_STRING, REGION_PATTERN_STRING,
                    COMPONENT_ACCOUNT_ID_PATTERN_STRING, COMPONENT_NAME_WITH_OR_WITHOUT_VERSION_PATTERN_STRING);
    private static final Pattern COMPONENT_ARN_PATTERN = Pattern.compile(COMPONENT_ARN_PATTERN_STRING);


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

        // iterating through the chain
        Throwable temp = e;
        while (temp != null) {
            translateExceptionToErrorCode(errorCodeSet, temp, errorContext);
            temp = temp.getCause();
        }

        List<String> errorStack = errorCodeSet.stream().map(Enum::toString).collect(Collectors.toList());
        // remove duplicate types
        List<String> errorTypes = errorCodeSet.stream().map(DeploymentErrorCode::getErrorType).distinct()
                .filter(type -> !type.equals(DeploymentErrorType.NONE)).map(Enum::toString)
                .collect(Collectors.toList());
        return new Pair<>(errorStack, errorTypes);
    }

    private static void translateExceptionToErrorCode(Set<DeploymentErrorCode> errorCodeSet, Throwable e,
                                                      Map<String, DeploymentErrorCode> errorContext) {
        if (e instanceof DeploymentException) {
            errorContext.putAll(((DeploymentException) e).getErrorContext());
            errorCodeSet.addAll(((DeploymentException) e).getErrorCodes());
        }
        if (e instanceof IOException) {
            collectErrorCodesFromIOException(errorCodeSet, (IOException) e, errorContext);
        } else if (e instanceof GreengrassV2DataException) {
            collectErrorCodesFromGreengrassV2DataException(errorCodeSet, (GreengrassV2DataException) e, errorContext);
        } else if (e instanceof S3Exception) {
            collectErrorCodesFromS3Exception(errorCodeSet, (S3Exception) e, errorContext);
        } else if (e instanceof ServiceLoadException) {
            collectErrorCodesFromServiceLoadException(errorCodeSet, errorContext);
        } else if (NETWORK_OFFLINE_EXCEPTION.stream().anyMatch(c -> c.isInstance(e))) {
            errorCodeSet.add(NETWORK_ERROR);
        } else if (e instanceof IllegalArgumentException) {
            errorCodeSet.add(errorContext.get(IllegalArgumentException.class.getSimpleName()));
        } else if (e instanceof InterruptedException) {
            errorCodeSet.add(DEPLOYMENT_INTERRUPTED);
        }
    }

    private static void collectErrorCodesFromIOException(Set<DeploymentErrorCode> errorCodeSet, IOException e,
                                                         Map<String, DeploymentErrorCode> errorContext) {
        errorCodeSet.add(IO_ERROR);
        if (e instanceof JsonMappingException || e instanceof JsonParseException) {
            errorCodeSet.add(IO_MAPPING_ERROR);
        } else if (e instanceof JsonProcessingException) {
            // JsonProcessingException is parent class of JsonMappingException and JsonParseException
            errorCodeSet.add(IO_WRITE_ERROR);
        }
        if (errorContext.containsKey(IOException.class.getSimpleName())) {
            errorCodeSet.add(errorContext.get(IOException.class.getSimpleName()));
        }
    }

    private static void collectErrorCodesFromGreengrassV2DataException(Set<DeploymentErrorCode> errorCodeSet,
                                                                       GreengrassV2DataException e,
                                                                       Map<String, DeploymentErrorCode> errorContext) {
        errorCodeSet.add(CLOUD_SERVICE_ERROR);
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
        if (errorContext.containsKey(GreengrassV2DataException.class.getSimpleName())) {
            errorCodeSet.add(errorContext.get(GreengrassV2DataException.class.getSimpleName()));
        }
    }


    private static void collectErrorCodesFromS3Exception(Set<DeploymentErrorCode> errorCodeSet, S3Exception e,
                                                         Map<String, DeploymentErrorCode> errorContext) {
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

        if (errorContext.containsKey(S3Exception.class.getSimpleName())) {
            errorCodeSet.add(errorContext.get(S3Exception.class.getSimpleName()));
        }
    }

    private static void collectErrorCodesFromServiceLoadException(Set<DeploymentErrorCode> errorCodeSet,
                                                                  Map<String, DeploymentErrorCode> errorContext) {
        errorCodeSet.add(LOCATE_INSTALLED_COMPONENT_ERROR);
        if (errorContext.containsKey(ServiceLoadException.class.getSimpleName())) {
            errorCodeSet.add(errorContext.get(ServiceLoadException.class.getSimpleName()));
        }
    }

    /**
     * Check whether a component is 1p.
     *
     * @param arn component arn from metadata
     * @return true if it's an AWS component
     * @throws PackageLoadingException unrecognized arn
     */
    public static boolean isAWSComponent(@NonNull String arn) throws PackageLoadingException {
        if (arn.isEmpty()) {
            throw new PackageLoadingException("Empty component arn is loaded");
        }

        final Matcher matcher = COMPONENT_ARN_PATTERN.matcher(arn);
        if (matcher.matches()) {
            String accountId = matcher.group(5);
            return "aws".equals(accountId);
        }
        throw new PackageLoadingException("Component arn loaded is not valid");
    }

}
