/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.Pair;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ArtifactDownloaderTest {

    private static final String LOCAL_FILE_NAME = "artifact.txt";

    @TempDir
    Path tempDir;

    Path artifactDir;

    static class MockDownloader extends ArtifactDownloader {
        final String localFileName = LOCAL_FILE_NAME;
        final String input;
        final AtomicBoolean cleanupCalled = new AtomicBoolean(false);

        MockDownloader(ComponentIdentifier identifier, ComponentArtifact artifact, Path artifactDir, String inputContent) {
            super(identifier, artifact, artifactDir);
            this.input = inputContent;
        }

        @Override
        protected String getLocalFileNameNoRetry() throws PackageDownloadException, RetryableException {
            return localFileName;
        }

        @Override
        protected Pair<InputStream, Runnable> readWithRange(long start, long end)
                throws PackageDownloadException, RetryableException {
            return new Pair<>(
                    new ByteArrayInputStream(Arrays.copyOfRange(input.getBytes(), (int) start, (int) end +1))
                    , () -> cleanupCalled.set(true));
        }

        @Override
        public Long getDownloadSizeNoRetry() throws PackageDownloadException, RetryableException {
            return (long) input.length();
        }
    }

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

        File file = downloader.downloadToPath();
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
        assertThat(file.toPath(), equalTo(artifactDir.resolve(LOCAL_FILE_NAME)));
        assertThat(downloader.getArtifactFile().toPath(), equalTo(artifactDir.resolve(LOCAL_FILE_NAME)));
        assertThat(downloader.cleanupCalled.get(), is(true));
        assertThat(downloader.downloadRequired(), is(false));
    }

    @Test
    void GIVEN_wrong_checksum_WHEN_download_to_path_THEN_fail() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("SHA-256", "invalidChecksum");

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        assertThrows(ArtifactChecksumMismatchException.class, downloader::downloadToPath);
        assertThat(downloader.cleanupCalled.get(), is(true));
    }

    @Test
    void GIVEN_wrong_algorithm_WHEN_download_to_path_THEN_fail() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("invalidAlgorithm", "invalidChecksum");

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        Exception e = assertThrows(ArtifactChecksumMismatchException.class, downloader::downloadToPath);
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

        File file = downloader.downloadToPath();

        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
        Object newInode = Files.getAttribute(localPartialFile.toPath(), "unix:ino");
        assertThat(newInode, equalTo(inode));
    }

    @Test
    void GIVEN_read_stream_WHEN_throw_RetryableException_THEN_retry() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        AtomicInteger invocationTimes = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            invocationTimes.incrementAndGet();
            if (invocationTimes.get() == 1) {
                throw new ArtifactDownloader.RetryableException("IOException");
            }
            return invocationOnMock.callRealMethod();
        }).when(downloader).readWithRange(anyLong(), anyLong());

        File file = downloader.downloadToPath();
        assertThat(invocationTimes.get(), equalTo(2));
        verify(downloader, times(2)).readWithRange(0, content.length() - 1);
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
    }

    @Test
    void GIVEN_read_from_returned_stream_WHEN_throw_IOException_THEN_retry() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        InputStream mockBrokenInputStream = mock(InputStream.class);
        when(mockBrokenInputStream.read(any())).thenThrow(new IOException());
        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        AtomicInteger invocationTimes = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            invocationTimes.incrementAndGet();
            if (invocationTimes.get() == 1) {
                return new Pair<InputStream, Runnable>(mockBrokenInputStream, () -> {});
            }
            return invocationOnMock.callRealMethod();
        }).when(downloader).readWithRange(anyLong(), anyLong());

        File file = downloader.downloadToPath();
        assertThat(invocationTimes.get(), equalTo(2));
        verify(downloader, times(2)).readWithRange(0, content.length() - 1);
        verify(mockBrokenInputStream).close();
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
                return invocationOnMock.getMethod().invoke(downloader, 0, 5);
            }
            return invocationOnMock.callRealMethod();
        }).when(downloader).readWithRange(anyLong(), anyLong());

        File file = downloader.downloadToPath();
        verify(downloader, times(1)).readWithRange(0, content.length() - 1);
        verify(downloader, times(1)).readWithRange(6, content.length() - 1);
        assertThat(Files.readAllBytes(file.toPath()), equalTo(content.getBytes()));
    }

    @Test
    void GIVEN_read_stream_WHEN_throw_PkgDownloadException_THEN_fail() throws Exception {
        String content = "Sample artifact content";
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        ComponentArtifact artifact = createTestArtifact("SHA-256", checksum);

        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));
        doAnswer(invocationOnMock -> {
            throw new PackageDownloadException("Fail to download");
        }).when(downloader).readWithRange(anyLong(), anyLong());

        assertThrows(PackageDownloadException.class, downloader::downloadToPath);
    }

    @Test
    void GIVEN_artifact_checksum_missing_WHEN_local_artifact_exist_THEN_use_local() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("SHA-256", null);

        MockDownloader downloader = new MockDownloader(createTestIdentifier(), artifact, artifactDir, content);
        Files.write(downloader.getArtifactFile().toPath(), "Sample local artifact content".getBytes());

        assertThat(downloader.downloadRequired(), is(false));
    }

    @Test
    void GIVEN_getDownloadSize_WHEN_throw_retryableError_THEN_should_retry() throws Exception {
        String content = "Sample artifact content";
        ComponentArtifact artifact = createTestArtifact("SHA-256", null);
        MockDownloader downloader = spy(new MockDownloader(createTestIdentifier(), artifact, artifactDir, content));

        AtomicInteger invocationCount = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            invocationCount.incrementAndGet();
            if (invocationCount.get() == 1) {
                throw new ArtifactDownloader.RetryableException("exception");
            }
            return (long) 100;
        }).when(downloader).getDownloadSizeNoRetry();

        assertThat(downloader.getDownloadSize(), is((long) 100));
        verify(downloader, times(2)).getDownloadSizeNoRetry();
    }

    private ComponentIdentifier createTestIdentifier() {
        return new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"));
    }

    private ComponentArtifact createTestArtifact(String algorithm, String checksum) throws URISyntaxException {
        return ComponentArtifact.builder()
                .algorithm(algorithm).checksum(checksum)
                .artifactUri(new URI("s3://eg-artifacts/ComponentWithS3Artifacts-1.0.0/artifact.txt")).build();
    }
}
