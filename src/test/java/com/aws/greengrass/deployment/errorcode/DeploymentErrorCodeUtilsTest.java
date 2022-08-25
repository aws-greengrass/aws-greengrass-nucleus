/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.InvalidImageOrAccessDeniedException;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2data.model.AccessDeniedException;
import software.amazon.awssdk.services.greengrassv2data.model.ConflictException;
import software.amazon.awssdk.services.greengrassv2data.model.InternalServerException;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;
import software.amazon.awssdk.services.greengrassv2data.model.ThrottlingException;
import software.amazon.awssdk.services.greengrassv2data.model.ValidationException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_BROKEN;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_UPDATE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_UNZIP_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.IO_WRITE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.MULTIPLE_NUCLEUS_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.S3_HEAD_OBJECT_ACCESS_DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeploymentErrorCodeUtilsTest {

    @Mock
    S3Exception s3Exception;

    @Mock
    ResourceNotFoundException resourceNotFoundException;

    @Mock
    AccessDeniedException accessDeniedException;

    @Mock
    ValidationException validationException;

    @Mock
    ThrottlingException throttlingException;

    @Mock
    ConflictException conflictException;

    @Mock
    InternalServerException internalServerException;

    @Mock
    JsonProcessingException jsonProcessingException;

    @Mock
    JsonMappingException jsonMappingException;

    @Mock
    SdkClientException sdkClientException;

    private static final String NUCLEUS_240_ARN =
            "arn:aws:greengrass:us-west-2:aws:components:aws.greengrass" + ".Nucleus:versions:2.4.0";

    private static final String USER_COMPONENT_ARN =
            "arn:aws:greengrass:us-east-1:123456789012:components:user" + ".component:versions:1.0.0";

    private static final String COMPONENT_ARN_INVALID_SERVICE =
            "arn:aws:s3:us-east-1:123456789012:components" + ":user.component:versions:1.0.0";

    private static final String COMPONENT_ARN_SHORT_ID =
            "arn:aws:greengrass:us-east-1:1234567890:components" + ":user.component:versions:1.0.0";

    @Test
    void GIVEN_internal_exception_WHEN_generate_error_report_THEN_expected_error_stack_and_types_returned() {
        // test an empty exception
        DeploymentException e = new DeploymentException("empty exception");
        testGenerateErrorReport(e, Collections.singletonList("DEPLOYMENT_FAILURE"), Collections.emptyList());

        // test an exception with inheritance hierarchy and an empty exception
        InvalidImageOrAccessDeniedException e1 = new InvalidImageOrAccessDeniedException("docker access denied", e);
        List<String> expectedStackFromE1 =
                Arrays.asList("DEPLOYMENT_FAILURE", "ARTIFACT_DOWNLOAD_ERROR", "DOCKER_ERROR",
                        "DOCKER_IMAGE_NOT_VALID");
        List<String> expectedTypesFromE1 = Collections.singletonList("DEPENDENCY_ERROR");
        testGenerateErrorReport(e1, expectedStackFromE1, expectedTypesFromE1);

        // test an arbitrary chain of exception, error stack should order from outside to inside
        List<DeploymentErrorCode> errorCodeList =
                Arrays.asList(IO_WRITE_ERROR, S3_HEAD_OBJECT_ACCESS_DENIED, MULTIPLE_NUCLEUS_ERROR, COMPONENT_BROKEN,
                        COMPONENT_UPDATE_ERROR);
        DeploymentException rootCause = e;
        for (DeploymentErrorCode errorCode : errorCodeList) {
            DeploymentException temp = new DeploymentException(errorCode);
            rootCause.initCause(temp);
            rootCause = temp;
        }
        List<String> expectedStackFromE2 =
                Arrays.asList("DEPLOYMENT_FAILURE", "IO_WRITE_ERROR", "S3_HEAD_OBJECT_ACCESS_DENIED",
                        "MULTIPLE_NUCLEUS_ERROR", "COMPONENT_BROKEN", "COMPONENT_UPDATE_ERROR");
        List<String> expectedTypesFromE2 =
                Arrays.asList("DEVICE_ERROR", "PERMISSION_ERROR", "NUCLEUS_ERROR", "COMPONENT_ERROR");
        testGenerateErrorReport(e, expectedStackFromE2, expectedTypesFromE2);

        // test a combination of inheritance and chain
        List<String> expectedStackFromCombined = Stream.concat(expectedStackFromE1.stream(),
                        expectedStackFromE2.stream().filter(code -> !"DEPLOYMENT_FAILURE".equals(code)))
                .collect(Collectors.toList());
        List<String> expectedTypesFromCombined =
                Stream.concat(expectedTypesFromE1.stream(), expectedTypesFromE2.stream()).collect(Collectors.toList());
        testGenerateErrorReport(e1, expectedStackFromCombined, expectedTypesFromCombined);

        // test with an additional error context
        rootCause.initCause(new IOException("some io unzip error"));
        e.withErrorContext(IOException.class.getSimpleName(), IO_UNZIP_ERROR);

        expectedStackFromCombined.addAll(Arrays.asList("IO_ERROR", "IO_UNZIP_ERROR"));
        testGenerateErrorReport(e1, expectedStackFromCombined, expectedTypesFromCombined);
    }

    @Test
    void GIVEN_external_exception_WHEN_generate_error_report_THEN_expected_error_stack_and_types_returned() {
        // test s3 exception
        when(s3Exception.statusCode()).thenReturn(502);
        testGenerateErrorReport(s3Exception, Arrays.asList("DEPLOYMENT_FAILURE", "S3_ERROR", "S3_SERVER_ERROR"),
                Arrays.asList("DEPENDENCY_ERROR", "SERVER_ERROR"));
        when(s3Exception.statusCode()).thenReturn(404);
        testGenerateErrorReport(s3Exception, Arrays.asList("DEPLOYMENT_FAILURE", "S3_ERROR", "S3_RESOURCE_NOT_FOUND"),
                Collections.singletonList("DEPENDENCY_ERROR"));
        when(s3Exception.statusCode()).thenReturn(403);
        testGenerateErrorReport(s3Exception, Arrays.asList("DEPLOYMENT_FAILURE", "S3_ERROR", "S3_ACCESS_DENIED"),
                Arrays.asList("DEPENDENCY_ERROR", "PERMISSION_ERROR"));
        when(s3Exception.statusCode()).thenReturn(429);
        testGenerateErrorReport(s3Exception, Arrays.asList("DEPLOYMENT_FAILURE", "S3_ERROR", "S3_BAD_REQUEST"),
                Collections.singletonList("DEPENDENCY_ERROR"));

        // test gg v2 data exception
        testGenerateErrorReport(resourceNotFoundException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "RESOURCE_NOT_FOUND"),
                Collections.singletonList("REQUEST_ERROR"));
        testGenerateErrorReport(accessDeniedException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "ACCESS_DENIED"),
                Collections.singletonList("PERMISSION_ERROR"));
        testGenerateErrorReport(validationException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "BAD_REQUEST"),
                Collections.singletonList("NUCLEUS_ERROR"));
        testGenerateErrorReport(throttlingException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "THROTTLING_ERROR"),
                Collections.singletonList("REQUEST_ERROR"));
        testGenerateErrorReport(conflictException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "CONFLICTED_REQUEST"),
                Collections.singletonList("REQUEST_ERROR"));
        testGenerateErrorReport(internalServerException,
                Arrays.asList("DEPLOYMENT_FAILURE", "CLOUD_SERVICE_ERROR", "SERVER_ERROR"),
                Collections.singletonList("SERVER_ERROR"));

        // test io exception
        testGenerateErrorReport(jsonMappingException,
                Arrays.asList("DEPLOYMENT_FAILURE", "IO_ERROR", "IO_MAPPING_ERROR"), Collections.emptyList());
        testGenerateErrorReport(jsonProcessingException,
                Arrays.asList("DEPLOYMENT_FAILURE", "IO_ERROR", "IO_WRITE_ERROR"),
                Collections.singletonList("DEVICE_ERROR"));

        // test network exception
        testGenerateErrorReport(sdkClientException, Arrays.asList("DEPLOYMENT_FAILURE", "NETWORK_ERROR"),
                Collections.singletonList("NETWORK_ERROR"));
    }

    @Test
    void GIVEN_valid_component_arn_WHEN_check_aws_component_THEN_check_correctly() throws PackageLoadingException {
        assertTrue(DeploymentErrorCodeUtils.isAWSComponent(NUCLEUS_240_ARN));
        assertFalse(DeploymentErrorCodeUtils.isAWSComponent(USER_COMPONENT_ARN));
    }

    @Test
    void GIVEN_invalid_component_arn_WHEN_check_aws_component_THEN_throw_proper_exception() {
        Exception e = assertThrows(PackageLoadingException.class, () -> DeploymentErrorCodeUtils.isAWSComponent(""));
        assertEquals("Empty component arn is loaded", e.getMessage());

        e = assertThrows(PackageLoadingException.class,
                () -> DeploymentErrorCodeUtils.isAWSComponent(COMPONENT_ARN_INVALID_SERVICE));
        assertEquals("Component arn loaded is not valid", e.getMessage());

        e = assertThrows(PackageLoadingException.class,
                () -> DeploymentErrorCodeUtils.isAWSComponent(COMPONENT_ARN_SHORT_ID));
        assertEquals("Component arn loaded is not valid", e.getMessage());
    }


    private static void testGenerateErrorReport(Throwable e, List<String> expectedErrorStack,
                                                List<String> expectedErrorTypes) {
        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(e);
        assertListEquals(errorReport.getLeft(), expectedErrorStack);
        assertListEqualsWithoutOrder(errorReport.getRight(), expectedErrorTypes);
    }

    private static void assertListEquals(List<String> first, List<String> second) {
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), second.get(i));
        }
    }

    private static void assertListEqualsWithoutOrder(List<String> first, List<String> second) {
        assertTrue(first.size() == second.size() && first.containsAll(second) && second.containsAll(first));
    }
}
