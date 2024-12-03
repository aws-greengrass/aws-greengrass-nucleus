/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

import lombok.Getter;

public enum DeploymentErrorCode {
    /* Generic types */
    DEPLOYMENT_FAILURE(DeploymentErrorType.NONE),
    DEPLOYMENT_REJECTED(DeploymentErrorType.NONE),
    DEPLOYMENT_INTERRUPTED(DeploymentErrorType.NONE),
    ARTIFACT_DOWNLOAD_ERROR(DeploymentErrorType.NONE),
    NO_AVAILABLE_COMPONENT_VERSION(DeploymentErrorType.NONE),
    COMPONENT_PACKAGE_LOADING_ERROR(DeploymentErrorType.NONE),

    /* Deployment request errors */
    REJECTED_STALE_DEPLOYMENT(DeploymentErrorType.NONE),
    NUCLEUS_MISSING_REQUIRED_CAPABILITIES(DeploymentErrorType.REQUEST_ERROR),
    COMPONENT_CIRCULAR_DEPENDENCY_ERROR(DeploymentErrorType.REQUEST_ERROR),
    UNAUTHORIZED_NUCLEUS_MINOR_VERSION_UPDATE(DeploymentErrorType.REQUEST_ERROR),
    MISSING_DOCKER_APPLICATION_MANAGER(DeploymentErrorType.REQUEST_ERROR),
    MISSING_TOKEN_EXCHANGE_SERVICE(DeploymentErrorType.REQUEST_ERROR),
    COMPONENT_VERSION_REQUIREMENTS_NOT_MET(DeploymentErrorType.REQUEST_ERROR),
    // deployment resolved multiple nucleus types
    MULTIPLE_NUCLEUS_RESOLVED_ERROR(DeploymentErrorType.REQUEST_ERROR),

    /* Greengrass cloud service errors */
    CLOUD_API_ERROR(DeploymentErrorType.NONE),
    BAD_REQUEST(DeploymentErrorType.NUCLEUS_ERROR),
    ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    THROTTLING_ERROR(DeploymentErrorType.REQUEST_ERROR),
    SERVER_ERROR(DeploymentErrorType.SERVER_ERROR),
    CONFLICTED_REQUEST(DeploymentErrorType.REQUEST_ERROR),
    RESOURCE_NOT_FOUND(DeploymentErrorType.REQUEST_ERROR),
    GET_DEPLOYMENT_CONFIGURATION_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    GET_COMPONENT_VERSION_ARTIFACT_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    RESOLVE_COMPONENT_CANDIDATES_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),

    /* Network / http */
    NETWORK_ERROR(DeploymentErrorType.NETWORK_ERROR),
    HTTP_REQUEST_ERROR(DeploymentErrorType.HTTP_ERROR),
    DOWNLOAD_DEPLOYMENT_DOCUMENT_ERROR(DeploymentErrorType.HTTP_ERROR),
    GET_GREENGRASS_ARTIFACT_SIZE_ERROR(DeploymentErrorType.HTTP_ERROR),
    DOWNLOAD_GREENGRASS_ARTIFACT_ERROR(DeploymentErrorType.HTTP_ERROR),

    /* IO errors */
    IO_ERROR(DeploymentErrorType.NONE),
    // it could be both recipe parse error or deployment doc error
    IO_MAPPING_ERROR(DeploymentErrorType.NONE),
    IO_WRITE_ERROR(DeploymentErrorType.DEVICE_ERROR),
    IO_READ_ERROR(DeploymentErrorType.DEVICE_ERROR),
    DISK_SPACE_CRITICAL(DeploymentErrorType.DEVICE_ERROR),
    IO_FILE_ATTRIBUTE_ERROR(DeploymentErrorType.DEVICE_ERROR),
    SET_PERMISSION_ERROR(DeploymentErrorType.DEVICE_ERROR),
    IO_UNZIP_ERROR(DeploymentErrorType.DEVICE_ERROR),

    /* Local file issues */
    LOCAL_RECIPE_NOT_FOUND(DeploymentErrorType.DEVICE_ERROR),
    LOCAL_RECIPE_CORRUPTED(DeploymentErrorType.DEVICE_ERROR),
    LOCAL_RECIPE_METADATA_NOT_FOUND(DeploymentErrorType.DEVICE_ERROR),
    // JVM hashing issue
    HASHING_ALGORITHM_UNAVAILABLE(DeploymentErrorType.DEVICE_ERROR),
    // Could be a local file issue or a Nucleus issue; we will categorize as the latter for visibility
    LAUNCH_DIRECTORY_CORRUPTED(DeploymentErrorType.DEVICE_ERROR),

    /* Component recipe errors */
    RECIPE_PARSE_ERROR(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    RECIPE_METADATA_PARSE_ERROR(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    ARTIFACT_URI_NOT_VALID(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    S3_ARTIFACT_URI_NOT_VALID(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    DOCKER_ARTIFACT_URI_NOT_VALID(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    EMPTY_ARTIFACT_URI(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    EMPTY_ARTIFACT_SCHEME(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    UNSUPPORTED_ARTIFACT_SCHEME(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    RECIPE_MISSING_MANIFEST(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    RECIPE_MISSING_ARTIFACT_HASH_ALGORITHM(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    ARTIFACT_CHECKSUM_MISMATCH(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    COMPONENT_DEPENDENCY_NOT_VALID(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    CONFIG_INTERPOLATE_ERROR(DeploymentErrorType.COMPONENT_RECIPE_ERROR),
    COMPONENT_VERSION_NOT_VALID(DeploymentErrorType.COMPONENT_RECIPE_ERROR),

    /* Config issues */
    DEVICE_CONFIG_NOT_VALID_FOR_ARTIFACT_DOWNLOAD(DeploymentErrorType.DEVICE_ERROR),
    RUN_WITH_CONFIG_NOT_VALID(DeploymentErrorType.REQUEST_ERROR),
    UNSUPPORTED_REGION(DeploymentErrorType.REQUEST_ERROR),
    IOT_CRED_ENDPOINT_FORMAT_NOT_VALID(DeploymentErrorType.REQUEST_ERROR),
    IOT_DATA_ENDPOINT_FORMAT_NOT_VALID(DeploymentErrorType.REQUEST_ERROR),

    /* Docker issues */
    DOCKER_ERROR(DeploymentErrorType.DEPENDENCY_ERROR),
    GET_ECR_CREDENTIAL_ERROR(DeploymentErrorType.PERMISSION_ERROR),
    USER_NOT_AUTHORIZED_FOR_DOCKER(DeploymentErrorType.PERMISSION_ERROR),
    DOCKER_SERVICE_UNAVAILABLE(DeploymentErrorType.DEPENDENCY_ERROR),
    DOCKER_IMAGE_QUERY_ERROR(DeploymentErrorType.DEPENDENCY_ERROR),
    DOCKER_LOGIN_ERROR(DeploymentErrorType.DEPENDENCY_ERROR),
    DOCKER_PULL_ERROR(DeploymentErrorType.DEPENDENCY_ERROR),
    DOCKER_IMAGE_NOT_VALID(DeploymentErrorType.DEPENDENCY_ERROR),

    /* S3 issues */
    S3_ERROR(DeploymentErrorType.DEPENDENCY_ERROR),
    S3_RESOURCE_NOT_FOUND(DeploymentErrorType.DEPENDENCY_ERROR),
    S3_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    S3_BAD_REQUEST(DeploymentErrorType.DEPENDENCY_ERROR),
    S3_SERVER_ERROR(DeploymentErrorType.SERVER_ERROR),
    S3_HEAD_OBJECT_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    S3_HEAD_OBJECT_RESOURCE_NOT_FOUND(DeploymentErrorType.REQUEST_ERROR),
    S3_GET_BUCKET_LOCATION_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    S3_GET_BUCKET_LOCATION_RESOURCE_NOT_FOUND(DeploymentErrorType.REQUEST_ERROR),
    S3_GET_OBJECT_ACCESS_DENIED(DeploymentErrorType.PERMISSION_ERROR),
    S3_GET_OBJECT_RESOURCE_NOT_FOUND(DeploymentErrorType.REQUEST_ERROR),

    /* Cloud service errors */
    // resolve component candidates returned more than one version
    RESOLVE_COMPONENT_CANDIDATES_BAD_RESPONSE(DeploymentErrorType.CLOUD_SERVICE_ERROR),
    DEPLOYMENT_DOCUMENT_SIZE_EXCEEDED(DeploymentErrorType.CLOUD_SERVICE_ERROR),
    GREENGRASS_ARTIFACT_SIZE_NOT_FOUND(DeploymentErrorType.CLOUD_SERVICE_ERROR),

    /* Errors that could be cloud errors OR nucleus errors */
    // An invalid deployment doc is received
    // it's a nucleus error if local deployment
    // a cloud service error is cloud deployment
    DEPLOYMENT_DOCUMENT_NOT_VALID(DeploymentErrorType.NONE),
    EMPTY_DEPLOYMENT_REQUEST(DeploymentErrorType.NONE),
    DEPLOYMENT_DOCUMENT_PARSE_ERROR(DeploymentErrorType.NONE),
    // unknown error since we don't know it's from local or cloud
    DEPLOYMENT_TYPE_NOT_VALID(DeploymentErrorType.UNKNOWN_ERROR),
    COMPONENT_METADATA_NOT_VALID_IN_DEPLOYMENT(DeploymentErrorType.NONE),

    /* Nucleus errors */
    NUCLEUS_VERSION_NOT_FOUND(DeploymentErrorType.NUCLEUS_ERROR),
    NUCLEUS_RESTART_FAILURE(DeploymentErrorType.NUCLEUS_ERROR),
    COMPONENT_LOAD_FAILURE(DeploymentErrorType.NUCLEUS_ERROR),

    /* Component issues */
    CUSTOM_PLUGIN_NOT_SUPPORTED(DeploymentErrorType.USER_COMPONENT_ERROR),
    COMPONENT_UPDATE_ERROR(DeploymentErrorType.NONE),
    COMPONENT_BROKEN(DeploymentErrorType.NONE),
    REMOVE_COMPONENT_ERROR(DeploymentErrorType.NONE),
    COMPONENT_BOOTSTRAP_TIMEOUT(DeploymentErrorType.NONE),
    COMPONENT_BOOTSTRAP_ERROR(DeploymentErrorType.NONE),
    COMPONENT_CONFIGURATION_NOT_VALID(DeploymentErrorType.NONE);


    @Getter
    private final DeploymentErrorType errorType;

    DeploymentErrorCode(DeploymentErrorType errorType) {
        this.errorType = errorType;
    }
}
