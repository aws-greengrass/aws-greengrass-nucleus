/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.S3SdkClientFactory;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class S3DownloaderTest {

    private static final String VALID_ARTIFACT_URI = "s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt";
    private static final String INVALID_ARTIFACT_URI = "s3/eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt";
    private static final String VALID_ARTIFACT_CHECKSUM = "StbR1g+686nCVhEJERUYNWhBqXskG6b3n9CG8vVekgM=";
    private static final String VALID_ALGORITHM = "SHA-256";
    private static final String VALID_ARTIFACT_CONTENT = "Sample artifact content";
    private static final String TEST_COMPONENT_NAME = "ComponentWithS3Artifacts";
    private static final String TEST_COMPONENT_VERSION = "1.0.0";
    private static final String TEST_SCOPE = "private";

    @TempDir
    static Path tempDir;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3SdkClientFactory s3SdkClientFactory;

    private S3Downloader s3Downloader;

    @BeforeEach
    void setup() {
        when(s3SdkClientFactory.getS3Client()).thenReturn(s3Client);
        lenient().when(s3SdkClientFactory.getClientForRegion(any())).thenReturn(s3Client);
        lenient().when(s3Client.getBucketLocation(any(GetBucketLocationRequest.class)))
                .thenReturn(mock(GetBucketLocationResponse.class));
        s3Downloader = new S3Downloader(s3SdkClientFactory);
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_download_to_path_THEN_succeed() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path artifactFilePath =
                Files.write(tempDir.resolve("artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        ResponseInputStream<GetObjectResponse> getObjectResponse = null;
        try {
            String checksum = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifactFilePath)));

            getObjectResponse = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(Files.readAllBytes(artifactFilePath))));
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(getObjectResponse);

            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            if (Files.notExists(saveToPath)) {
                Files.createDirectories(saveToPath);
            }
            s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(VALID_ARTIFACT_URI), checksum, VALID_ALGORITHM, null), saveToPath);
            byte[] downloadedFile = Files.readAllBytes(saveToPath.resolve("artifact.txt"));
            assertThat("Content of downloaded file should be same as the artifact content",
                    Arrays.equals(Files.readAllBytes(artifactFilePath), downloadedFile));
        } finally {
            if (getObjectResponse != null) {
                getObjectResponse.close();
            }
            ComponentTestResourceHelper.cleanDirectory(testCache);
            ComponentTestResourceHelper.cleanDirectory(artifactFilePath);
        }
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_download_recipe_with_no_checksum_specified_THEN_use_local() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        try {
            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            if (Files.notExists(saveToPath)) {
                Files.createDirectories(saveToPath);
            }
            Files.write(saveToPath.resolve("artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                    StandardCharsets.UTF_8);
            s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(VALID_ARTIFACT_URI), null, null, null), saveToPath);
            verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        } finally {
            ComponentTestResourceHelper.cleanDirectory(testCache);
        }
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_uri_THEN_fail() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        try {
            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            assertThrows(InvalidArtifactUriException.class, () -> s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(INVALID_ARTIFACT_URI), "somechecksum", VALID_ALGORITHM, null),
                    saveToPath));
        } finally {
            ComponentTestResourceHelper.cleanDirectory(testCache);
        }
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_checksum_THEN_fail() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path artifactFilePath =
                Files.write(tempDir.resolve("artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        ResponseInputStream<GetObjectResponse> getObjectResponse = null;
        try {
            String checksum = Base64.getEncoder().encodeToString("WrongChecksum".getBytes(StandardCharsets.UTF_8));

            getObjectResponse = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(Files.readAllBytes(artifactFilePath))));
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(getObjectResponse);

            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            if (Files.notExists(saveToPath)) {
                Files.createDirectories(saveToPath);
            }
            assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(VALID_ARTIFACT_URI), checksum, VALID_ALGORITHM, null), saveToPath));
        } finally {
            if (getObjectResponse != null) {
                getObjectResponse.close();
            }
            ComponentTestResourceHelper.cleanDirectory(testCache);
            ComponentTestResourceHelper.cleanDirectory(artifactFilePath);
        }

    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_algorithm_THEN_fail() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path artifactFilePath =
                Files.write(tempDir.resolve("artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        ResponseInputStream<GetObjectResponse> getObjectResponse = null;
        try {
            String checksum = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifactFilePath)));

            getObjectResponse = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(Files.readAllBytes(artifactFilePath))));
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(getObjectResponse);

            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            if (Files.notExists(saveToPath)) {
                Files.createDirectories(saveToPath);
            }
            assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(VALID_ARTIFACT_URI), checksum, "WrongAlgorithm", null), saveToPath));
        } finally {
            if (getObjectResponse != null) {
                getObjectResponse.close();
            }
            ComponentTestResourceHelper.cleanDirectory(testCache);
            ComponentTestResourceHelper.cleanDirectory(artifactFilePath);
        }
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_error_in_getting_from_s3_THEN_fail() throws Exception {
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        try {
            when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.class);

            Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
            if (Files.notExists(saveToPath)) {
                Files.createDirectories(saveToPath);
            }
            assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                    new ComponentIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                    new ComponentArtifact(new URI(VALID_ARTIFACT_URI), VALID_ARTIFACT_CHECKSUM, VALID_ALGORITHM, null),
                    saveToPath));
        } finally {
            ComponentTestResourceHelper.cleanDirectory(testCache);
        }
    }
}
