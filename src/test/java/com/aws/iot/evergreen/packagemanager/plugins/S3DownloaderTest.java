package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.TestHelper;
import com.aws.iot.evergreen.packagemanager.exceptions.InvalidArtifactUriException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.tes.TokenExchangeService;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.S3SdkClientFactory;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith({MockitoExtension.class, EGExtension.class})
public class S3DownloaderTest {

    private static final String VALID_ARTIFACT_URI = "s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt";
    private static final String INVALID_ARTIFACT_URI = "s3/eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt";
    private static final String VALID_ARTIFACT_CHECKSUM =
            "U0hBLTI1NmNoZWNrc3VtOxzvv73vv701ECM4MO+/vWBpJO+/vWXvv70sYu+/vQA8Au+/vSXvv70zR++/vWDvv70S77+9";
    private static final String VALID_ARTIFACT_CONTENT = "Sample artifact content";
    private static final String TEST_COMPONENT_NAME = "ComponentWithS3Artifacts";
    private static final String TEST_COMPONENT_VERSION = "1.0.0";
    private static final String TEST_SCOPE = "private";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3SdkClientFactory s3SdkClientFactory;

    @Mock
    private Kernel kernel;

    private S3Downloader s3Downloader;

    @BeforeEach
    void setup() {
        when(s3SdkClientFactory.getS3Client()).thenReturn(s3Client);
        s3Downloader = new S3Downloader(s3SdkClientFactory, kernel);
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_download_to_path_THEN_succeed() throws Exception {
        Path artifactFilePath =
                Files.write(Paths.get("/tmp/artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String checksum = new String(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifactFilePath)),
                StandardCharsets.UTF_8);
        String checksumWithAlgorithm = "SHA-256checksum" + checksum;
        String encoded = Base64.getEncoder().encodeToString(checksumWithAlgorithm.getBytes(StandardCharsets.UTF_8));

        Context mockContext = mock(Context.class);
        when(mockContext.get(TokenExchangeService.class)).thenReturn(mock(TokenExchangeService.class));
        when(kernel.getContext()).thenReturn(mockContext);
        ResponseBytes responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(Files.readAllBytes(artifactFilePath));
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
        Path testCache = TestHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
        if (Files.notExists(saveToPath)) {
            Files.createDirectories(saveToPath);
        }
        s3Downloader.downloadToPath(
                new PackageIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                new ComponentArtifact(new URI(VALID_ARTIFACT_URI), encoded), saveToPath);
        byte[] downloadedFile = Files.readAllBytes(saveToPath.resolve("artifact.txt"));
        assertThat("Content of downloaded file should be same as the artifact content",
                Arrays.equals(Files.readAllBytes(artifactFilePath), downloadedFile));
        TestHelper.cleanDirectory(testCache);
        TestHelper.cleanDirectory(artifactFilePath);
        mockContext.close();
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_uri_THEN_fail() throws Exception {
        Path testCache = TestHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
        assertThrows(InvalidArtifactUriException.class, () -> s3Downloader.downloadToPath(
                new PackageIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                new ComponentArtifact(new URI(INVALID_ARTIFACT_URI), "somechecksum"), saveToPath));
        TestHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_checksum_THEN_fail() throws Exception {
        Path artifactFilePath =
                Files.write(Paths.get("/tmp/artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String checksumWithAlgorithm = "SHA-256checksumRandomString";
        String encoded = Base64.getEncoder().encodeToString(checksumWithAlgorithm.getBytes(StandardCharsets.UTF_8));

        Context mockContext = mock(Context.class);
        when(mockContext.get(TokenExchangeService.class)).thenReturn(mock(TokenExchangeService.class));
        when(kernel.getContext()).thenReturn(mockContext);
        ResponseBytes responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(Files.readAllBytes(artifactFilePath));
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
        Path testCache = TestHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
        if (Files.notExists(saveToPath)) {
            Files.createDirectories(saveToPath);
        }
        assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                new PackageIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                new ComponentArtifact(new URI(VALID_ARTIFACT_URI), encoded), saveToPath));
        TestHelper.cleanDirectory(testCache);
        TestHelper.cleanDirectory(artifactFilePath);
        mockContext.close();
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_bad_algorithm_THEN_fail() throws Exception {
        Path artifactFilePath =
                Files.write(Paths.get("/tmp/artifact.txt"), Collections.singletonList(VALID_ARTIFACT_CONTENT),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String checksum = new String(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifactFilePath)),
                StandardCharsets.UTF_8);
        String checksumWithAlgorithm = "RandomAlgorithmchecksum" + checksum;
        String encoded = Base64.getEncoder().encodeToString(checksumWithAlgorithm.getBytes(StandardCharsets.UTF_8));

        Context mockContext = mock(Context.class);
        when(mockContext.get(TokenExchangeService.class)).thenReturn(mock(TokenExchangeService.class));
        when(kernel.getContext()).thenReturn(mockContext);
        ResponseBytes responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(Files.readAllBytes(artifactFilePath));
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
        Path testCache = TestHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
        assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                new PackageIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                new ComponentArtifact(new URI(VALID_ARTIFACT_URI), encoded), saveToPath));
        TestHelper.cleanDirectory(testCache);
        TestHelper.cleanDirectory(artifactFilePath);
        mockContext.close();
    }

    @Test
    void GIVEN_s3_artifact_uri_WHEN_error_in_getting_from_s3_THEN_fail() throws Exception {
        Context mockContext = mock(Context.class);
        when(mockContext.get(TokenExchangeService.class)).thenReturn(mock(TokenExchangeService.class));
        when(kernel.getContext()).thenReturn(mockContext);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(S3Exception.class);
        Path testCache = TestHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve(TEST_COMPONENT_NAME).resolve(TEST_COMPONENT_VERSION);
        assertThrows(PackageDownloadException.class, () -> s3Downloader.downloadToPath(
                new PackageIdentifier(TEST_COMPONENT_NAME, new Semver(TEST_COMPONENT_VERSION), TEST_SCOPE),
                new ComponentArtifact(new URI(VALID_ARTIFACT_URI), VALID_ARTIFACT_CHECKSUM), saveToPath));
        TestHelper.cleanDirectory(testCache);
        mockContext.close();
    }
}
