/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.BOOTSTRAP_TASK_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.DEPLOYMENT_METADATA_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.MAX_PROCESSING_ATTEMPTS;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.ROLLBACK_SNAPSHOT_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.TARGET_CONFIG_FILE;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentDirectoryManagerTest {
    private static final String mockArn = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final String expectedDirectoryName =
            "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.1";
    @TempDir
    Path deploymentsDir;
    @Mock
    private Kernel kernel;
    @Mock
    private NucleusPaths nucleusPaths;

    private DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentsDir).when(nucleusPaths).deploymentPath();
        deploymentDirectoryManager = new DeploymentDirectoryManager(kernel, nucleusPaths);
    }

    @Test
    void WHEN_create_new_deployment_dir_THEN_setup_directory_and_symlink() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertThat(actual.toFile(), anExistingDirectory());
        assertEquals(deploymentsDir.resolve(expectedDirectoryName), actual);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
    }

    @Test
    void GIVEN_deployment_dir_exists_WHEN_create_new_deployment_dir_THEN_reset_directory_and_symlink() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        Path oldFile = actual.resolve("oldfile");
        Files.createFile(oldFile);
        assertThat(oldFile.toFile(), anExistingFile());
        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertThat(oldFile.toFile(), anExistingFile());
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), anExistingDirectory());

        createNewDeploymentDir(mockArn);
        assertThat(oldFile.toFile(), not(anExistingFileOrDirectory()));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName), actual);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_deployment_succeeds_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);

        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousFailureDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_deployment_fails_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_previous_deployment_WHEN_new_deployment_finishes_THEN_cleanup_previous_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();

        String mockArn2 = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:2";
        String expectedDirectoryName2 =
                "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.2";
        Path actual2 = createNewDeploymentDir(mockArn2);
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        assertThat(actual1.toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousFailureDir().toFile(), not(anExistingFileOrDirectory()));
        assertEquals(actual2, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName2), actual2);
    }

    @Test
    void GIVEN_deployment_WHEN_write_to_file_and_read_THEN_restore_deployment(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        ignoreExceptionOfType(context, JsonEOFException.class);

        Path actual1 = createNewDeploymentDir(mockArn);
        DeploymentDocument document = mock(DeploymentDocument.class);
        doReturn("mockId").when(document).getDeploymentId();
        Deployment expected = new Deployment(document, Deployment.DeploymentType.IOT_JOBS, "mockId", DEFAULT);
        deploymentDirectoryManager.writeDeploymentMetadata(expected);
        Path metadataFile = actual1.resolve(DEPLOYMENT_METADATA_FILE);
        assertThat(metadataFile.toFile(), anExistingFile());
        Deployment actual = deploymentDirectoryManager.readDeploymentMetadata();
        assertEquals(expected, actual);

        try (CommitableWriter writer = CommitableWriter.commitOnClose(metadataFile)) {
            writer.write("{\"corrupted\"");
        }
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "~").toFile(), anExistingFile());
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "+").toFile(), not(anExistingFile()));
        Deployment backup = deploymentDirectoryManager.readDeploymentMetadata();
        assertEquals(expected, backup);
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "~").toFile(), not(anExistingFile()));
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "+").toFile(), not(anExistingFile()));

        try (CommitableWriter writer = CommitableWriter.commitOnClose(metadataFile)) {
            writer.write("again failure to write");
        }
        Deployment backupAgain = deploymentDirectoryManager.readDeploymentMetadata();
        assertEquals(expected, backupAgain);
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "~").toFile(), not(anExistingFile()));
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE + "+").toFile(), not(anExistingFile()));
    }

    @Test
    void GIVEN_file_path_WHEN_take_config_snapshot_THEN_call_kernel() throws Exception {
        deploymentDirectoryManager.takeConfigSnapshot(mock(Path.class));
        verify(kernel, times(1)).writeEffectiveConfigAsTransactionLog(any());
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_get_file_THEN_resolve_path() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertEquals(actual.resolve(ROLLBACK_SNAPSHOT_FILE), deploymentDirectoryManager.getSnapshotFilePath());
        assertEquals(actual.resolve(TARGET_CONFIG_FILE), deploymentDirectoryManager.getTargetConfigFilePath());
        assertEquals(actual.resolve(BOOTSTRAP_TASK_FILE), deploymentDirectoryManager.getBootstrapTaskFilePath());
    }

    @Test
    void GIVEN_unfinished_deployment_WHEN_same_deployment_reprocessed_THEN_rollback_snapshot_and_attempts_carried_forward()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        Files.write(first.resolve(ROLLBACK_SNAPSHOT_FILE), "verified".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, deploymentDirectoryManager.incrementAndGetProcessingAttempts());

        // No persistLast*Deployment call, so the ongoing link survives: the deployment never completed.
        Path second = createNewDeploymentDir(mockArn);

        assertEquals(first, second);
        assertEquals("verified",
                new String(Files.readAllBytes(second.resolve(ROLLBACK_SNAPSHOT_FILE)), StandardCharsets.UTF_8));
        assertEquals(2, deploymentDirectoryManager.incrementAndGetProcessingAttempts());
    }

    @Test
    void GIVEN_unfinished_deployment_WHEN_superseded_THEN_rollback_snapshot_carried_forward_but_attempts_reset()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        Files.write(first.resolve(ROLLBACK_SNAPSHOT_FILE), "verified".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, deploymentDirectoryManager.incrementAndGetProcessingAttempts());

        Path second = createNewDeploymentDir(mockArn + "-superseding");

        assertEquals("verified",
                new String(Files.readAllBytes(second.resolve(ROLLBACK_SNAPSHOT_FILE)), StandardCharsets.UTF_8));
        // A different deployment gets its own attempt budget.
        assertEquals(1, deploymentDirectoryManager.incrementAndGetProcessingAttempts());
    }

    @Test
    void GIVEN_completed_deployment_WHEN_new_deployment_starts_THEN_nothing_carried_forward() throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        Files.write(first.resolve(ROLLBACK_SNAPSHOT_FILE), "verified".getBytes(StandardCharsets.UTF_8));
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        Path second = createNewDeploymentDir(mockArn + "-next");

        assertThat(second.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
        assertEquals(1, deploymentDirectoryManager.incrementAndGetProcessingAttempts());
    }

    @Test
    void GIVEN_carried_forward_snapshot_WHEN_take_rollback_snapshot_THEN_snapshot_kept() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), "verified".getBytes(StandardCharsets.UTF_8));

        deploymentDirectoryManager.takeRollbackSnapshot();

        verify(kernel, times(0)).writeEffectiveConfigAsTransactionLog(any());
        assertEquals("verified",
                new String(Files.readAllBytes(dir.resolve(ROLLBACK_SNAPSHOT_FILE)), StandardCharsets.UTF_8));
    }

    @Test
    void GIVEN_no_snapshot_WHEN_take_rollback_snapshot_THEN_snapshot_written() throws Exception {
        createNewDeploymentDir(mockArn);

        deploymentDirectoryManager.takeRollbackSnapshot();

        verify(kernel, times(1)).writeEffectiveConfigAsTransactionLog(deploymentDirectoryManager.getSnapshotFilePath());
    }

    @Test
    void WHEN_deployment_completes_THEN_no_unfinished_deployment_reported() throws Exception {
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
        createNewDeploymentDir(mockArn);
        assertTrue(deploymentDirectoryManager.hasUnfinishedDeployment());
        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
    }

    @Test
    void GIVEN_deployment_processed_repeatedly_WHEN_attempts_reach_the_cap_THEN_attempts_reported_exhausted()
            throws Exception {
        createNewDeploymentDir(mockArn);

        for (int attempt = 1; attempt < MAX_PROCESSING_ATTEMPTS; attempt++) {
            assertEquals(attempt, deploymentDirectoryManager.incrementAndGetProcessingAttempts());
            assertFalse(deploymentDirectoryManager.hasExhaustedProcessingAttempts());
        }
        assertEquals(MAX_PROCESSING_ATTEMPTS, deploymentDirectoryManager.incrementAndGetProcessingAttempts());
        assertTrue(deploymentDirectoryManager.hasExhaustedProcessingAttempts());
    }

    @Test
    void GIVEN_deployment_failed_for_exhausted_attempts_WHEN_redelivered_THEN_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        for (int attempt = 0; attempt < MAX_PROCESSING_ATTEMPTS; attempt++) {
            deploymentDirectoryManager.incrementAndGetProcessingAttempts();
        }
        deploymentDirectoryManager.persistLastFailedDeployment();

        assertTrue(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn));
        // A different deployment is unaffected, so a new revision still gets processed.
        assertFalse(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn + "-next"));
    }

    @Test
    void GIVEN_deployment_failed_with_attempts_remaining_WHEN_redelivered_THEN_not_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.incrementAndGetProcessingAttempts();
        deploymentDirectoryManager.persistLastFailedDeployment();

        assertFalse(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn));
    }

    @Test
    void GIVEN_deployment_succeeded_WHEN_redelivered_THEN_not_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        for (int attempt = 0; attempt < MAX_PROCESSING_ATTEMPTS; attempt++) {
            deploymentDirectoryManager.incrementAndGetProcessingAttempts();
        }
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        assertFalse(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn));
    }

    @Test
    void GIVEN_another_deployment_finished_WHEN_refused_deployment_redelivered_THEN_no_longer_recognised()
            throws Exception {
        createNewDeploymentDir(mockArn);
        for (int attempt = 0; attempt < MAX_PROCESSING_ATTEMPTS; attempt++) {
            deploymentDirectoryManager.incrementAndGetProcessingAttempts();
        }
        deploymentDirectoryManager.persistLastFailedDeployment();
        assertTrue(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn));

        // A later deployment reaching a terminal state takes over the last-failed and last-successful
        // links, which is what keeps the device able to accept new revisions. The refused deployment is
        // consequently forgotten: a re-delivery of it arriving this late is treated as a new request. That
        // is bounded elsewhere — a cloud deployment older than the group's last one is rejected as stale,
        // and a local deployment is never re-delivered.
        createNewDeploymentDir(mockArn + "-next");
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        assertFalse(deploymentDirectoryManager.wasRefusedForExhaustedAttempts(mockArn));
    }

    private Path createNewDeploymentDir(String arn) throws IOException {
        return deploymentDirectoryManager.createNewDeploymentDirectory(arn);
    }
}
