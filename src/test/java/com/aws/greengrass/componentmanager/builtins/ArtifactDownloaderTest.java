/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ArtifactDownloaderTest {

    private static final String LOCAL_FILE_NAME = "artifact.txt";

    @TempDir
    Path tempDir;

    Path artifactDir;

    @BeforeEach
    public void setup() throws IOException {
        artifactDir = tempDir.resolve("artifacts");
        // clean up artifacts dir
        Files.deleteIfExists(artifactDir);
        Files.createDirectories(artifactDir);
    }

    @Test
    void GIVEN_input_correct_WHEN_download_to_path_THEN_succeed() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm("SHA-256").checksum(checksum)
                .artifactUri(new URI("s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt")).build();

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        assertThat(downloader.downloadRequired(), is(true));

        File file = downloader.download();
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
        assertThat(file.toPath(), equalTo(artifactDir.resolve(LOCAL_FILE_NAME)));
        assertThat(downloader.getArtifactFile().toPath(), equalTo(artifactDir.resolve(LOCAL_FILE_NAME)));
        assertThat(downloader.downloadRequired(), is(false));
    }

    @Test
    void GIVEN_wrong_checksum_WHEN_download_to_path_THEN_fail(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ArtifactChecksumMismatchException.class);

        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("SHA-256", "invalidChecksum");

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        downloader.setChecksumMismatchRetryConfig(RetryUtils.RetryConfig.builder().maxAttempt(2)
                .retryableExceptions(Arrays.asList(ArtifactChecksumMismatchException.class)).build());
        assertThrows(PackageDownloadException.class, downloader::download);
    }

    @Test
    void GIVEN_wrong_algorithm_WHEN_download_to_path_THEN_fail() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("invalidAlgorithm", "invalidChecksum");

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        Exception e = assertThrows(ArtifactChecksumMismatchException.class, downloader::download);
        assertThat(e.getMessage(), containsString("checksum is not supported"));
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void GIVEN_artifact_partial_exist_WHEN_download_THEN_resume() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);

        File localPartialFile = downloader.getArtifactFile();
        Files.write(localPartialFile.toPath(), "Sample".getBytes());
        Object inode = Files.getAttribute(localPartialFile.toPath(), "unix:ino");

        File file = downloader.download();

        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
        Object newInode = Files.getAttribute(localPartialFile.toPath(), "unix:ino");
        assertThat(newInode, equalTo(inode));
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void GIVEN_existing_artifact_corrupt_WHEN_download_THEN_retry(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ArtifactChecksumMismatchException.class);

        String content = "Sample artifact content";
        String checksum =
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));

        File localPartialFile = downloader.getArtifactFile();
        Files.write(localPartialFile.toPath(), "Foo".getBytes());
        downloader.setChecksumMismatchRetryConfig(RetryUtils.RetryConfig.builder().maxAttempt(2)
                .retryableExceptions(Arrays.asList(ArtifactChecksumMismatchException.class)).build());
        File file = downloader.download();

        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
        verify(downloader).download(eq((long) 3), eq((long) content.length() - 1), any());
        verify(downloader).download(eq((long) 0), eq((long) content.length() - 1), any());
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_read_from_returned_stream_WHEN_throw_IOException_THEN_retry(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, IOException.class);
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        InputStream mockBrokenInputStream = spy(new ByteArrayInputStream(content.getBytes()));
        AtomicInteger invocationTimes = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            invocationTimes.incrementAndGet();
            if (invocationTimes.get() == 1) {
                throw new IOException();
            }
            return invocationOnMock.callRealMethod();
        }).when(mockBrokenInputStream).read(any());

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        downloader.overridingInputStream = mockBrokenInputStream;

        File file = downloader.download();
        // one fail read, one successful read, and one read that returns -1
        assertThat(invocationTimes.get(), equalTo(3));
        verify(downloader, times(2))
                .download(eq((long)0), eq((long)content.length() - 1), any());
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
    }

    @Test
    void GIVEN_read_from_returned_stream_WHEN_return_partial_THEN_retry() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        AtomicInteger invocationTimes = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            invocationTimes.incrementAndGet();
            if (invocationTimes.get() == 1) {
                return invocationOnMock.getMethod().invoke(downloader, 0, 5, invocationOnMock.getArgument(2));
            }
            return invocationOnMock.callRealMethod();
        }).when(downloader).download(anyLong(), anyLong(), any());

        File file = downloader.download();
        verify(downloader, times(1))
                .download(eq((long) 0), eq((long)content.length() - 1), any());
        verify(downloader, times(1))
                .download(eq((long)6), eq((long)content.length() - 1), any());
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
    }

    @Test
    void GIVEN_download_WHEN_throw_PkgDownloadException_THEN_fail() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        doAnswer(invocationOnMock -> {
            throw new PackageDownloadException("Fail to download");
        }).when(downloader).download(anyLong(), anyLong(), any());

        assertThrows(PackageDownloadException.class, () -> downloader.download());
    }

    @Test
    void GIVEN_checksum_match_WHEN_download_required_THEN_return_false() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm("SHA-256").checksum(checksum)
                .artifactUri(new URI("s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt")).build();

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);

        File file = downloader.getArtifactFile();
        Files.write(file.toPath(), content.getBytes());
        assertThat(downloader.downloadRequired(), is(false));
    }

    @Test
    void GIVEN_artifact_checksum_missing_WHEN_local_artifact_exist_THEN_use_local() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("SHA-256", null);

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        Files.write(downloader.getArtifactFile().toPath(), "Sample local artifact content".getBytes());

        assertThat(downloader.downloadRequired(), is(false));
    }

    private ComponentIdentifier createTestIdentifier() {
        return new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"));
    }

    private ComponentArtifact createTestArtifact(String algorithm, String checksum) throws URISyntaxException {
        return ComponentArtifact.builder().algorithm(algorithm).checksum(checksum)
                .artifactUri(new URI("s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt")).build();
    }

    static class MockDownloader extends ArtifactDownloader {
        final String localFileName = LOCAL_FILE_NAME;
        final String input;
        InputStream overridingInputStream = null;

        MockDownloader(ComponentIdentifier identifier, ComponentArtifact artifact, Path artifactDir,
                       String inputContent) {
            super(identifier, artifact, artifactDir);
            this.input = inputContent;
        }

        @Override
        protected String getArtifactFilename() {
            return localFileName;
        }

        @Override
        protected long download(long start, long end, MessageDigest digest) throws PackageDownloadException {
            if (overridingInputStream != null) {
                return super.download(overridingInputStream, digest);
            }
            return super.download(
                    new ByteArrayInputStream(Arrays.copyOfRange(input.getBytes(), (int) start, (int) end + 1)), digest);
        }

        @Override
        public Optional<String> checkDownloadable() {
            return Optional.empty();
        }

        @Override
        public Long getDownloadSize() {
            return (long) input.length();
        }
    }
}
