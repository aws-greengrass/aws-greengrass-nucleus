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
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.BOOTSTRAP_TASK_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.DEPLOYMENT_METADATA_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.PROCESSING_STATE_FILE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.ROLLBACK_SNAPSHOT_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.TARGET_CONFIG_FILE;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessageSubstring;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({GGExtension.class, MockitoExtension.class})
// The interrupted-deployment cases take this class past PMD's 1000-line limit. Splitting it up is worth doing on
// its own, not as part of a bug fix.
@SuppressWarnings("PMD.ExcessiveClassLength")
class DeploymentDirectoryManagerTest {
    private static final String mockArn = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final String expectedDirectoryName =
            "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.1";
    private static final String SUPERSEDING_ARN = mockArn + "-superseding";
    // A rollback snapshot as the nucleus writes one: a transaction log, which a carried snapshot must validate as.
    private static final String VERIFIED_SNAPSHOT =
            "{\"TS\":1602000000000,\"TP\":[\"services\",\"com.example.Component\",\"version\"],\"W\":\"changed\","
                    + "\"V\":\"1.0.0\"}\n";
    // A different, equally valid snapshot, for telling which directory a recovery picked.
    private static final String COPIED_SNAPSHOT =
            "{\"TS\":1602000000001,\"TP\":[\"services\",\"com.example.Component\",\"version\"],\"W\":\"changed\","
                    + "\"V\":\"1.0.1\"}\n";
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
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn);
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

        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousFailureDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_deployment_fails_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_previous_deployment_WHEN_new_deployment_finishes_THEN_cleanup_previous_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);

        String mockArn2 = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:2";
        String expectedDirectoryName2 =
                "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.2";
        Path actual2 = createNewDeploymentDir(mockArn2);
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn2);

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
        writeVerifiedSnapshot(first);
        assertEquals(1, recordAttempt());

        // No persistLast*Deployment call, so the ongoing link survives: the deployment never completed.
        Path second = createNewDeploymentDir(mockArn);

        assertEquals(first, second);
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        assertEquals(2, recordAttempt());
    }

    @Test
    void GIVEN_unfinished_deployment_WHEN_superseded_THEN_rollback_snapshot_carried_forward_but_attempts_reset()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        assertEquals(1, recordAttempt());

        Path second = createNewDeploymentDir(SUPERSEDING_ARN);

        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        // A different deployment gets its own attempt budget, and its record says the snapshot was carried.
        assertTrue((Boolean) readRecord(second).get("rollbackSnapshotCarried"));
        assertEquals(1, recordAttempt(SUPERSEDING_ARN));
    }

    @Test
    void GIVEN_completed_deployment_WHEN_new_deployment_starts_THEN_nothing_carried_forward() throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn);

        Path second = createNewDeploymentDir(mockArn + "-next");

        assertThat(second.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
        assertEquals(1, recordAttempt(mockArn + "-next"));
    }

    @Test
    void GIVEN_carried_forward_snapshot_WHEN_take_rollback_snapshot_THEN_snapshot_kept() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(dir);

        deploymentDirectoryManager.takeRollbackSnapshot(mockArn);

        verify(kernel, times(0)).writeEffectiveConfigAsTransactionLog(any());
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(dir));
    }

    @Test
    void GIVEN_no_snapshot_WHEN_take_rollback_snapshot_THEN_snapshot_written() throws Exception {
        createNewDeploymentDir(mockArn);
        kernelWritesSnapshot(VERIFIED_SNAPSHOT);

        deploymentDirectoryManager.takeRollbackSnapshot(mockArn);

        verify(kernel, times(1)).writeEffectiveConfigAsTransactionLog(deploymentDirectoryManager.getSnapshotFilePath());
    }

    @Test
    void WHEN_deployment_completes_THEN_no_unfinished_deployment_reported() throws Exception {
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
        createNewDeploymentDir(mockArn);
        assertTrue(deploymentDirectoryManager.hasUnfinishedDeployment());
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn);
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
    }

    @Test
    void GIVEN_deployment_processed_repeatedly_WHEN_attempts_reach_the_cap_THEN_attempts_reported_exhausted()
            throws Exception {
        createNewDeploymentDir(mockArn);

        for (int attempt = 1; attempt < DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS; attempt++) {
            assertEquals(attempt, recordAttempt());
            assertNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
        }
        assertEquals(DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, recordAttempt());
        assertEquals(DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS,
                deploymentDirectoryManager.exhaustedProcessingState(mockArn).attemptsMade());
        // Only the deployment the ongoing link names has a processing state.
        assertNull(deploymentDirectoryManager.exhaustedProcessingState(SUPERSEDING_ARN));
    }

    @Test
    void GIVEN_deployment_failed_for_exhausted_attempts_WHEN_redelivered_THEN_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        exhaustAttempts();
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);

        assertNotNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
        // A different deployment is unaffected, so a new revision still gets processed.
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn + "-next"));
    }

    @Test
    void GIVEN_deployment_failed_with_attempts_remaining_WHEN_redelivered_THEN_not_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        recordAttempt();
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);

        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    @Test
    void GIVEN_deployment_succeeded_WHEN_redelivered_THEN_not_recognised() throws Exception {
        createNewDeploymentDir(mockArn);
        exhaustAttempts();
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn);

        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    @Test
    void GIVEN_another_deployment_finished_WHEN_refused_deployment_redelivered_THEN_no_longer_recognised()
            throws Exception {
        createNewDeploymentDir(mockArn);
        exhaustAttempts();
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);
        assertNotNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));

        // A later deployment reaching a terminal state takes over the last-failed and last-successful
        // links, which is what keeps the device able to accept new revisions. The refused deployment is
        // consequently forgotten: a re-delivery of it arriving this late is treated as a new request. That
        // is bounded elsewhere — a cloud deployment older than the group's last one is rejected as stale,
        // and a local deployment is never re-delivered.
        createNewDeploymentDir(mockArn + "-next");
        deploymentDirectoryManager.persistLastSuccessfulDeployment(mockArn + "-next");

        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    @Test
    void GIVEN_no_attempt_limit_configured_WHEN_deployment_applied_repeatedly_THEN_attempts_never_exhausted()
            throws Exception {
        createNewDeploymentDir(mockArn);

        // Below 1 means no limit: a device whose environment is not ready yet keeps retrying rather than
        // being restored and failed.
        for (int attempt = 1; attempt <= DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS + 5; attempt++) {
            assertEquals(attempt, deploymentDirectoryManager.recordProcessingAttempt(mockArn, 0, true));
            assertNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
        }
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    @Test
    void GIVEN_attempt_limit_lowered_WHEN_deployment_applied_THEN_the_limit_in_force_applies() throws Exception {
        createNewDeploymentDir(mockArn);

        // The limit recorded with the attempt is the one that decides, so a deployment is judged by the
        // limit its operator had configured when it was applied.
        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 1, true);

        assertNotNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
    }

    @Test
    void GIVEN_limit_recorded_WHEN_later_attempt_reads_another_limit_THEN_first_limit_kept() throws Exception {
        createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 1, true);

        // The second attempt reads the limit from live configuration, which by then holds this deployment's own
        // unverified configuration: it must not be able to lift its own cap.
        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 0, true);

        assertNotNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
        assertEquals(1, deploymentDirectoryManager.exhaustedProcessingState(mockArn).attemptLimit());
    }

    @Test
    void GIVEN_no_deployment_directory_WHEN_attempt_recorded_THEN_refused() throws Exception {
        assertThrows(IOException.class, this::recordAttempt);
        assertNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
    }

    // ---- The attempt record: one atomic file, safe defaults ----

    @Test
    void GIVEN_attempt_recorded_THEN_count_limit_and_policy_kept_in_one_record_and_no_temporary_file_left()
            throws Exception {
        Path dir = createNewDeploymentDir(mockArn);

        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 5, false);
        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 5, false);

        Map<String, Object> record = readRecord(dir);
        assertEquals(2, record.get("processingAttempts"));
        assertEquals(5, record.get("processingAttemptLimit"));
        assertFalse((Boolean) record.get("rollbackOnFailure"));
        assertThat(dir.resolve(PROCESSING_STATE_FILE + "+").toFile(), not(anExistingFileOrDirectory()));
        // The previous version is kept as the backup the reader falls back to.
        assertEquals(1, readJson(dir.resolve(PROCESSING_STATE_FILE + "~")).get("processingAttempts"));
    }

    @Test
    void GIVEN_deployment_directory_from_a_nucleus_without_the_record_THEN_no_attempts_and_default_limit()
            throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(DEPLOYMENT_METADATA_FILE), "{}".getBytes(StandardCharsets.UTF_8));

        assertNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
        assertEquals(1, recordAttempt());
    }

    @Test
    void GIVEN_record_without_a_limit_WHEN_count_reaches_the_default_THEN_exhausted() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        // Written by another version of the nucleus, with a field this one does not know: absent fields read as
        // their defaults, so the default limit applies rather than none.
        writeRecord(dir, "{\"processingAttempts\":" + DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS
                + ",\"launchRecovery\":\"SOMETHING_NEWER\",\"futureField\":1}");

        DeploymentDirectoryManager.ProcessingState exhausted =
                deploymentDirectoryManager.exhaustedProcessingState(mockArn);

        assertNotNull(exhausted);
        assertEquals(DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, exhausted.attemptLimit());
        // A decision this version does not know reads as one it cannot repeat, not as none.
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.UNRECOGNIZED, exhausted.getLaunchRecovery());
    }

    @Test
    void GIVEN_record_and_backup_unreadable_WHEN_deployment_applied_again_THEN_counted_under_the_default_limit(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        ignoreExceptionOfType(context, MismatchedInputException.class);
        Path dir = createNewDeploymentDir(mockArn);
        // Even an explicit "no limit" must not survive the record becoming unreadable.
        deploymentDirectoryManager.recordProcessingAttempt(mockArn, 0, true);
        Files.write(dir.resolve(PROCESSING_STATE_FILE), new byte[]{0, 0, 0});
        Files.write(dir.resolve(PROCESSING_STATE_FILE + "~"), "{\"processingAttempts\":".getBytes(
                StandardCharsets.UTF_8));

        assertNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
        for (int attempt = 1; attempt <= DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS; attempt++) {
            assertEquals(attempt, recordAttempt());
        }
        assertNotNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
    }

    @Test
    void GIVEN_record_torn_WHEN_read_THEN_previous_version_used(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonEOFException.class);
        ignoreExceptionOfType(context, MismatchedInputException.class);
        Path dir = createNewDeploymentDir(mockArn);
        exhaustAttempts();
        // A record torn after it was committed: the reader falls back to the version before it, which counted
        // one attempt fewer, so this attempt is counted as the last allowed one again rather than as the first.
        Files.write(dir.resolve(PROCESSING_STATE_FILE), "{\"processingAttem".getBytes(StandardCharsets.UTF_8));

        assertEquals(DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, recordAttempt());
        assertNotNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn));
    }

    @Test
    void GIVEN_attempt_cannot_be_persisted_WHEN_recorded_THEN_fails_rather_than_losing_count() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        // A directory where the record's temporary file has to go makes the write fail.
        Files.createDirectories(dir.resolve(PROCESSING_STATE_FILE + "+").resolve("occupied"));

        assertThrows(IOException.class, this::recordAttempt);
    }

    // ---- Carrying the rollback snapshot forward, fail-safe ----

    @Test
    void GIVEN_record_commit_leaves_previous_record_WHEN_attempt_recorded_THEN_fails_rather_than_losing_count()
            throws Exception {
        DeploymentDirectoryManager failingCommit = spy(deploymentDirectoryManager);
        Path dir = failingCommit.createNewDeploymentDirectory(mockArn);
        failingCommit.recordProcessingAttempt(mockArn, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);
        // The commit "succeeds" without replacing the record, as when CommitableFile logs a failed rename.
        doAnswer(invocation -> null).when(failingCommit).commitProcessingState(any(), any());

        IOException e = assertThrows(IOException.class, () -> failingCommit.recordProcessingAttempt(mockArn,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true));

        assertThat(e.getMessage(), org.hamcrest.Matchers.containsString("was not persisted"));
        assertEquals(1, readRecord(dir).get("processingAttempts"));
    }

    @Test
    void GIVEN_unfinished_snapshot_invalid_WHEN_same_deployment_reprocessed_THEN_fails_and_snapshot_kept(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path first = createNewDeploymentDir(mockArn);
        Files.write(first.resolve(ROLLBACK_SNAPSHOT_FILE), "not a transaction log".getBytes(StandardCharsets.UTF_8));
        recordAttempt();

        DeploymentDirectoryManager.UnusableRollbackSnapshotException e = assertThrows(
                DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> createNewDeploymentDir(mockArn));

        assertTrue(e.isSameDeployment());
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals("not a transaction log", readSnapshot(first));
        assertEquals(1, readRecord(first).get("processingAttempts"));
    }

    @Test
    void GIVEN_unfinished_snapshot_invalid_WHEN_superseded_THEN_fails_once_and_unfinished_deployment_set_aside(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        ignoreExceptionOfType(context, DeploymentDirectoryManager.UnusableRollbackSnapshotException.class);
        Path first = createNewDeploymentDir(mockArn);
        Files.write(first.resolve(ROLLBACK_SNAPSHOT_FILE), "not a transaction log".getBytes(StandardCharsets.UTF_8));
        recordAttempt();

        DeploymentDirectoryManager.UnusableRollbackSnapshotException e = assertThrows(
                DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> createNewDeploymentDir(SUPERSEDING_ARN));

        // A lasting condition, so the unfinished deployment is moved off the ongoing link on purpose, kept as
        // evidence and recorded as set aside rather than as a reported failure. Nothing half-made is left.
        assertFalse(e.isSameDeployment());
        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertEquals("ABANDONED", readRecord(first).get("terminalOutcome"));
        assertEquals("not a transaction log", readSnapshot(first));
        assertThat(deploymentsDir.resolve(expectedDirectoryName + "-superseding").toFile(),
                not(anExistingFileOrDirectory()));
        // It was never reported, so a re-delivery of it is not refused even though it used an attempt.
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));

        // The next deployment is not blocked: it starts from live configuration.
        Path next = createNewDeploymentDir(SUPERSEDING_ARN);
        assertEquals(next, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertThat(next.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_unfinished_deployment_counted_an_attempt_but_has_no_snapshot_WHEN_superseded_THEN_unusable(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, DeploymentDirectoryManager.UnusableRollbackSnapshotException.class);
        // An attempt is counted only once the snapshot validated, so a counted attempt without a snapshot means the
        // snapshot was lost after the merge: there is no verified configuration to carry, and live config is not one.
        createNewDeploymentDir(mockArn);
        recordAttempt();

        assertThrows(DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> createNewDeploymentDir(SUPERSEDING_ARN));
    }

    @Test
    void GIVEN_same_deployment_used_up_its_attempts_with_an_unusable_snapshot_WHEN_redelivered_THEN_reused_for_its_failure(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), "not a transaction log".getBytes(StandardCharsets.UTF_8));
        exhaustAttempts();
        deploymentDirectoryManager.recordLaunchRecovery(
                DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE);

        // Exhaustion decides first: the re-delivery is failed with what the launch recorded, not as a carry failure.
        assertEquals(dir, createNewDeploymentDir(mockArn));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE,
                deploymentDirectoryManager.exhaustedProcessingState(mockArn).getLaunchRecovery());
    }

    @Test
    void GIVEN_snapshot_copy_fails_WHEN_superseded_THEN_fails_and_unfinished_deployment_kept() throws Exception {
        DeploymentDirectoryManager failingCopy = spy(deploymentDirectoryManager);
        doThrow(new IOException("disk full")).when(failingCopy).writeCarriedSnapshot(any(), any());
        Path first = failingCopy.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);

        IOException e = assertThrows(IOException.class,
                () -> failingCopy.createNewDeploymentDirectory(SUPERSEDING_ARN));

        // Transient: the snapshot is fine, so the unfinished deployment keeps it for the next attempt.
        assertFalse(e instanceof DeploymentDirectoryManager.UnusableRollbackSnapshotException);
        assertEquals("disk full", e.getCause().getMessage());
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(first));
        assertThat(deploymentsDir.resolve(expectedDirectoryName + "-superseding").toFile(),
                not(anExistingFileOrDirectory()));
        verify(kernel, times(0)).writeEffectiveConfigAsTransactionLog(any());
    }

    @Test
    void GIVEN_carried_copy_does_not_validate_WHEN_superseded_THEN_fails_and_unfinished_deployment_kept(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        DeploymentDirectoryManager corruptingCopy = spy(deploymentDirectoryManager);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(1)).write("garbled".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(corruptingCopy).writeCarriedSnapshot(any(), any());
        Path first = corruptingCopy.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);

        IOException e = assertThrows(IOException.class,
                () -> corruptingCopy.createNewDeploymentDirectory(SUPERSEDING_ARN));

        assertFalse(e instanceof DeploymentDirectoryManager.UnusableRollbackSnapshotException);
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(first));
    }

    @Test
    void GIVEN_nucleus_stopping_WHEN_unfinished_snapshot_carried_THEN_reported_as_interrupted_and_state_kept()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);

        // Writes through a channel fail on an interrupted thread. An interruption says nothing about the snapshot,
        // so it must not be treated as an unusable one, and the carry stops before changing anything.
        assertThrows(InterruptedIOException.class, () -> {
            Thread.currentThread().interrupt();
            try {
                createNewDeploymentDir(SUPERSEDING_ARN);
            } finally {
                assertTrue(Thread.interrupted(), "the interruption is preserved for the caller");
            }
        });

        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(first));
        assertThat(deploymentsDir.resolve(expectedDirectoryName + "-superseding").toFile(),
                not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_nucleus_stopping_WHEN_same_deployment_reprocessed_THEN_its_records_are_read_and_kept()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        recordAttempt();

        // Re-processing in place only reads and deletes, neither of which an interrupt can fail.
        Path second;
        Thread.currentThread().interrupt();
        try {
            second = createNewDeploymentDir(mockArn);
        } finally {
            assertTrue(Thread.interrupted(), "the interruption is preserved for the caller");
        }

        assertEquals(first, second);
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        assertEquals(1, readRecord(second).get("processingAttempts"));
    }

    @Test
    void GIVEN_unfinished_deployment_interrupted_before_its_snapshot_WHEN_superseded_THEN_live_config_snapshotted()
            throws Exception {
        // Interrupted before it took a snapshot, and so before it counted an attempt or changed anything: live
        // config is still the verified one, which is unlike a snapshot that exists but cannot be carried.
        createNewDeploymentDir(mockArn);
        kernelWritesSnapshot(VERIFIED_SNAPSHOT);

        Path second = createNewDeploymentDir(SUPERSEDING_ARN);
        assertThat(second.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
        deploymentDirectoryManager.takeRollbackSnapshot(SUPERSEDING_ARN);

        verify(kernel).writeEffectiveConfigAsTransactionLog(second.resolve(ROLLBACK_SNAPSHOT_FILE));
    }

    @Test
    void GIVEN_unfinished_deployment_WHEN_superseded_THEN_link_moves_and_unfinished_directory_removed()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);

        Path second = createNewDeploymentDir(SUPERSEDING_ARN);

        assertEquals(second, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertThat(first.toFile(), not(anExistingFileOrDirectory()));
        assertEquals(Arrays.asList(PROCESSING_STATE_FILE, ROLLBACK_SNAPSHOT_FILE), fileNamesIn(second));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_same_deployment_reprocessed_THEN_directory_reused_and_only_its_records_kept() throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        recordAttempt();
        recordAttempt();
        for (String leftover : Arrays.asList(DEPLOYMENT_METADATA_FILE, DEPLOYMENT_METADATA_FILE + "~",
                BOOTSTRAP_TASK_FILE, TARGET_CONFIG_FILE, ROLLBACK_SNAPSHOT_FILE + "+", "unknown")) {
            Files.write(first.resolve(leftover), "stale".getBytes(StandardCharsets.UTF_8));
        }

        Path second = createNewDeploymentDir(mockArn);

        assertTrue(Files.isSameFile(first, second));
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        // The metadata stays until DeploymentService rewrites it, so a local deployment interrupted in between
        // can still be re-processed.
        assertEquals(Arrays.asList(DEPLOYMENT_METADATA_FILE, DEPLOYMENT_METADATA_FILE + "~", PROCESSING_STATE_FILE,
                PROCESSING_STATE_FILE + "~", ROLLBACK_SNAPSHOT_FILE), fileNamesIn(second));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        assertEquals(3, recordAttempt());
    }

    @Test
    void GIVEN_stale_switch_links_WHEN_new_deployment_directory_created_THEN_removed_without_following_links()
            throws Exception {
        createNewDeploymentDir(mockArn);
        Path linkedDir = Files.createDirectories(deploymentsDir.resolve("linked"));
        Files.write(linkedDir.resolve("keep"), "keep".getBytes(StandardCharsets.UTF_8));
        // Links a switch left behind while the ongoing link exists: it completed, or it never replaced the link.
        Path temporaryLink = Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-switch-stale"), linkedDir);
        Path displacedLink = Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-displaced-stale"), linkedDir);

        createNewDeploymentDir(mockArn);

        assertFalse(Files.exists(temporaryLink, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(displacedLink, LinkOption.NOFOLLOW_LINKS));
        // Removing a stale link must not remove what it pointed to.
        assertThat(linkedDir.resolve("keep").toFile(), anExistingFile());
        assertEquals(deploymentsDir.resolve(expectedDirectoryName),
                Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
    }

    // ---- Switching the ongoing link where a link cannot be renamed over another (Windows) ----

    @Test
    void GIVEN_link_cannot_be_replaced_in_one_step_WHEN_superseded_THEN_switched_in_two_renames() throws Exception {
        DeploymentDirectoryManager twoStep = spy(deploymentDirectoryManager);
        failRenamesOverTheOngoingLink(twoStep);
        Path first = twoStep.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);

        Path second = twoStep.createNewDeploymentDirectory(SUPERSEDING_ARN);

        assertEquals(second, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        assertThat(first.toFile(), not(anExistingFileOrDirectory()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_two_step_switch_WHEN_moving_the_new_link_in_fails_THEN_old_link_renamed_back() throws Exception {
        DeploymentDirectoryManager twoStep = spy(deploymentDirectoryManager);
        Path first = twoStep.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);
        // Renaming the replacement link fails both over the ongoing link, as on Windows, and into its vacated name.
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            if (String.valueOf(source.getFileName()).startsWith(".ongoing-switch-")) {
                throw new IOException("Access is denied");
            }
            Files.move(source, (Path) invocation.getArgument(1));
            return null;
        }).when(twoStep).rename(any(), any(), anyBoolean());

        assertThrows(IOException.class, () -> twoStep.createNewDeploymentDirectory(SUPERSEDING_ARN));

        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(first));
        assertThat(deploymentsDir.resolve(expectedDirectoryName + "-superseding").toFile(),
                not(anExistingFileOrDirectory()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_two_step_switch_WHEN_every_rename_back_fails_THEN_a_link_to_the_unfinished_deployment_remains_and_is_recovered()
            throws Exception {
        DeploymentDirectoryManager twoStep = spy(deploymentDirectoryManager);
        Path first = twoStep.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);
        // Only the first rename, of the ongoing link aside, works; everything after it fails.
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            if (source.equals(deploymentDirectoryManager.getOngoingDir())) {
                Files.move(source, (Path) invocation.getArgument(1));
                return null;
            }
            throw new IOException("Access is denied");
        }).when(twoStep).rename(any(), any(), anyBoolean());

        assertThrows(IOException.class, () -> twoStep.createNewDeploymentDirectory(SUPERSEDING_ARN));

        // No ongoing link, but the displaced link still names the unfinished deployment and its snapshot.
        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(first));

        // The next deployment (or launch) undoes the switch and carries the snapshot forward.
        Path next = deploymentDirectoryManager.createNewDeploymentDirectory(SUPERSEDING_ARN);
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(next));
        assertEquals(next, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_two_step_switch_WHEN_setting_the_old_link_aside_fails_THEN_nothing_moves() throws Exception {
        DeploymentDirectoryManager twoStep = spy(deploymentDirectoryManager);
        Path first = twoStep.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);
        doThrow(new IOException("Access is denied")).when(twoStep).rename(any(), any(), anyBoolean());

        assertThrows(IOException.class, () -> twoStep.createNewDeploymentDirectory(SUPERSEDING_ARN));

        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_superseded_directory_cannot_be_deleted_WHEN_switched_THEN_new_link_valid_and_old_directory_left(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "The process cannot access the file");
        DeploymentDirectoryManager failingDelete = spy(deploymentDirectoryManager);
        Path first = failingDelete.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);
        // An open handle stops the superseded directory from being deleted once the link has moved, as on Windows.
        doAnswer(invocation -> {
            throw new IOException("The process cannot access the file");
        }).when(failingDelete).deleteRecursively(first);

        Path second = failingDelete.createNewDeploymentDirectory(SUPERSEDING_ARN);

        // The switch stands: the ongoing link names the new directory and its validated copy of the snapshot. The
        // old directory is left behind, unreferenced, rather than failing a deployment that is ready.
        assertEquals(second, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(second));
        assertThat(first.toFile(), anExistingDirectory());
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_crash_after_old_link_removed_WHEN_next_deployment_THEN_switch_rolled_forward_and_carry_kept()
            throws Exception {
        // What a two-step switch leaves when the nucleus stops between its two steps: no ongoing link, and the
        // replacement link naming the superseding directory, which holds the validated copy of the snapshot. The
        // copy is given content of its own here, so which way the switch is finished shows.
        Path unfinished = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(unfinished);
        recordAttempt();
        Path superseding = Files.createDirectories(deploymentsDir.resolve(expectedDirectoryName + "-superseding"));
        Files.write(superseding.resolve(ROLLBACK_SNAPSHOT_FILE), COPIED_SNAPSHOT.getBytes(StandardCharsets.UTF_8));
        Files.delete(deploymentDirectoryManager.getOngoingDir());
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-switch-crashed"), superseding);
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-displaced-crashed"), unfinished);

        assertTrue(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).isUnfinishedDeployment());
        assertEquals(superseding, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));

        Path next = createNewDeploymentDir(mockArn + "-next");
        assertEquals(COPIED_SNAPSHOT, readSnapshot(next));
        assertEquals(next, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_crash_mid_switch_and_replacement_lost_its_snapshot_WHEN_next_deployment_THEN_switch_undone_and_carry_kept()
            throws Exception {
        // The replacement link survived, but what it names no longer holds the copy: deleting that directory was
        // cut short, or the copy was lost. Finishing the switch onto it would leave no verified snapshot.
        Path unfinished = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(unfinished);
        recordAttempt();
        Path superseding = Files.createDirectories(deploymentsDir.resolve(expectedDirectoryName + "-superseding"));
        Files.delete(deploymentDirectoryManager.getOngoingDir());
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-switch-crashed"), superseding);
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-displaced-crashed"), unfinished);

        assertTrue(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).isUnfinishedDeployment());
        assertEquals(unfinished, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));

        Path next = createNewDeploymentDir(mockArn + "-next");
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(next));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    @Test
    void GIVEN_two_step_switch_WHEN_both_renames_fail_and_new_directory_only_partly_deleted_THEN_next_deployment_carries_snapshot()
            throws Exception {
        DeploymentDirectoryManager twoStep = spy(deploymentDirectoryManager);
        Path first = twoStep.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(first);
        recordAttempt();
        // Only the first rename, of the ongoing link aside, works; everything after it fails.
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            if (source.equals(deploymentDirectoryManager.getOngoingDir())) {
                Files.move(source, (Path) invocation.getArgument(1));
                return null;
            }
            throw new IOException("Access is denied");
        }).when(twoStep).rename(any(), any(), anyBoolean());
        // An open handle stops the new directory from being deleted after its snapshot already was.
        Path superseding = deploymentsDir.resolve(expectedDirectoryName + "-superseding");
        doAnswer(invocation -> {
            Files.deleteIfExists(superseding.resolve(ROLLBACK_SNAPSHOT_FILE));
            throw new IOException("The process cannot access the file");
        }).when(twoStep).deleteRecursively(superseding);

        assertThrows(IOException.class, () -> twoStep.createNewDeploymentDirectory(SUPERSEDING_ARN));

        // Whatever is left of the new directory, the next deployment (or launch) recovers the unfinished one.
        Path next = deploymentDirectoryManager.createNewDeploymentDirectory(mockArn + "-next");
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(next));
        assertEquals(next, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
    }

    @Test
    void GIVEN_unfinished_deployment_carried_a_snapshot_that_is_gone_WHEN_superseded_THEN_unusable(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessageSubstring(context, "carried a rollback snapshot forward that is no longer there");
        // It carried a verified snapshot forward, and only that snapshot told the verified configuration apart.
        Path carrying = createNewDeploymentDir(mockArn);
        writeRecord(carrying, "{\"rollbackSnapshotCarried\":true}");

        assertThrows(DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> createNewDeploymentDir(SUPERSEDING_ARN));
    }

    @Test
    void GIVEN_crash_mid_switch_with_unusable_replacement_WHEN_launch_assessed_THEN_switch_undone(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path unfinished = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(unfinished);
        recordAttempt();
        Path superseding = Files.createDirectories(deploymentsDir.resolve(expectedDirectoryName + "-superseding"));
        Files.write(superseding.resolve(ROLLBACK_SNAPSHOT_FILE), "torn".getBytes(StandardCharsets.UTF_8));
        Files.delete(deploymentDirectoryManager.getOngoingDir());
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-switch-crashed"), superseding);
        Files.createSymbolicLink(deploymentsDir.resolve(".ongoing-displaced-crashed"), unfinished);

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertTrue(assessment.isUnfinishedDeployment());
        assertEquals(unfinished, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(Collections.singletonList("ongoing"), linkNamesIn(deploymentsDir));
    }

    // ---- Identity: only the deployment the ongoing link names is touched ----

    @Test
    void GIVEN_superseding_deployment_not_yet_linked_WHEN_it_finishes_THEN_unfinished_deployment_untouched()
            throws Exception {
        Path unfinished = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(unfinished);
        recordAttempt();

        // The superseding deployment failed before its directory replaced the unfinished one's.
        deploymentDirectoryManager.recordTerminalStatus(SUPERSEDING_ARN, DeploymentDirectoryManager.TerminalOutcome.FAILED);
        deploymentDirectoryManager.persistLastFailedDeployment(SUPERSEDING_ARN,
                DeploymentDirectoryManager.TerminalOutcome.FAILED);
        deploymentDirectoryManager.persistLastSuccessfulDeployment(SUPERSEDING_ARN);

        assertEquals(unfinished, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertNull(readRecord(unfinished).get("terminalOutcome"));
        assertFalse(Files.exists(deploymentDirectoryManager.getPreviousFailureDir(), LinkOption.NOFOLLOW_LINKS));
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
        // The next deployment still carries the unfinished deployment's snapshot.
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(createNewDeploymentDir(mockArn + "-next")));
    }

    @Test
    void GIVEN_link_moved_to_superseding_deployment_WHEN_late_activator_writes_THEN_refused() throws Exception {
        Path unfinished = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(unfinished);
        Path superseding = createNewDeploymentDir(SUPERSEDING_ARN);

        // The cancelled deployment's activator, which cancel(false) does not interrupt, gets this far late.
        assertThrows(IOException.class, () -> deploymentDirectoryManager.takeRollbackSnapshot(mockArn));
        assertThrows(IOException.class, () -> deploymentDirectoryManager.recordProcessingAttempt(mockArn,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true));

        assertNull(readRecord(superseding).get("processingAttempts"));
        verify(kernel, times(0)).writeEffectiveConfigAsTransactionLog(any());
    }

    // ---- Snapshot durability ----

    @Test
    void GIVEN_snapshot_write_leaves_nothing_on_disk_WHEN_rollback_snapshot_taken_THEN_fails() throws Exception {
        createNewDeploymentDir(mockArn);
        // CommitableFile logs rather than throws a failed rename, so the dump "succeeds" with only "+" on disk:
        // here the mocked kernel writes nothing at all.

        assertThrows(IOException.class, () -> deploymentDirectoryManager.takeRollbackSnapshot(mockArn));
    }

    @Test
    void GIVEN_snapshot_written_torn_WHEN_rollback_snapshot_taken_THEN_fails_and_removed(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, JsonMappingException.class);
        Path dir = createNewDeploymentDir(mockArn);
        kernelWritesSnapshot("{\"TS\":1602000000000,\"TP\":[\"services\"");

        assertThrows(IOException.class, () -> deploymentDirectoryManager.takeRollbackSnapshot(mockArn));

        // Taken from live configuration before anything changed, so the next attempt can simply take it again.
        assertThat(dir.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_kept_snapshot_corrupted_WHEN_rollback_snapshot_taken_THEN_unusable(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), "not a transaction log".getBytes(StandardCharsets.UTF_8));

        assertThrows(DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> deploymentDirectoryManager.takeRollbackSnapshot(mockArn));
        verify(kernel, times(0)).writeEffectiveConfigAsTransactionLog(any());
    }

    @Test
    void GIVEN_nucleus_stopping_WHEN_rollback_snapshot_dumped_THEN_dump_discarded_as_interrupted() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        // A dump an interrupt cut short at a line boundary validates although it is incomplete.
        doAnswer(invocation -> {
            Files.write((Path) invocation.getArgument(0), VERIFIED_SNAPSHOT.getBytes(StandardCharsets.UTF_8));
            Thread.currentThread().interrupt();
            return null;
        }).when(kernel).writeEffectiveConfigAsTransactionLog(any());

        try {
            assertThrows(InterruptedIOException.class, () -> deploymentDirectoryManager.takeRollbackSnapshot(mockArn));
        } finally {
            assertTrue(Thread.interrupted(), "the interruption is preserved for the caller");
        }
        assertThat(dir.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
    }

    // ---- Stale state from an earlier deployment with the same ID ----

    @Test
    void GIVEN_outdated_directory_cannot_be_removed_WHEN_same_id_deployed_again_THEN_stale_state_not_adopted()
            throws Exception {
        DeploymentDirectoryManager failingDelete = spy(deploymentDirectoryManager);
        Path dir = failingDelete.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(dir);
        failingDelete.recordProcessingAttempt(mockArn, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);
        failingDelete.persistLastSuccessfulDeployment(mockArn);
        // Nothing in it can be removed, as with an open handle on Windows or a directory without write permission.
        doAnswer(invocation -> {
            throw new IOException("Access is denied");
        }).when(failingDelete).deleteRecursively(any());

        assertThrows(IOException.class, () -> failingDelete.createNewDeploymentDirectory(mockArn),
                "a new deployment must not adopt the stale snapshot and attempt record");

        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void GIVEN_outdated_directory_only_partly_removable_WHEN_same_id_deployed_again_THEN_stale_state_removed(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "The process cannot access the file");
        DeploymentDirectoryManager failingDelete = spy(deploymentDirectoryManager);
        Path dir = failingDelete.createNewDeploymentDirectory(mockArn);
        writeVerifiedSnapshot(dir);
        failingDelete.recordProcessingAttempt(mockArn, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);
        failingDelete.persistLastSuccessfulDeployment(mockArn);
        Files.write(dir.resolve("artifact-held-open"), "x".getBytes(StandardCharsets.UTF_8));
        // An open handle stops the directory from being removed, but not the records next to it.
        doThrow(new IOException("The process cannot access the file")).when(failingDelete).deleteRecursively(dir);

        Path again = failingDelete.createNewDeploymentDirectory(mockArn);

        assertEquals(dir, again);
        assertThat(dir.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
        assertThat(dir.resolve(PROCESSING_STATE_FILE).toFile(), not(anExistingFileOrDirectory()));
        assertNull(failingDelete.exhaustedProcessingState(mockArn));
    }

    // ---- A deployment that reached a terminal status before its directory moved ----

    @Test
    void GIVEN_terminal_status_recorded_WHEN_link_move_interrupted_THEN_next_deployment_carries_nothing()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        recordAttempt();
        // DeploymentService records the terminal status before publishing it; the nucleus stops before the
        // directory moves off the ongoing link.
        deploymentDirectoryManager.recordTerminalStatus(mockArn, DeploymentDirectoryManager.TerminalOutcome.SUCCEEDED);
        kernelWritesSnapshot(VERIFIED_SNAPSHOT);

        Path second = createNewDeploymentDir(mockArn + "-next");
        assertThat(second.resolve(ROLLBACK_SNAPSHOT_FILE).toFile(), not(anExistingFileOrDirectory()));
        deploymentDirectoryManager.takeRollbackSnapshot(mockArn + "-next");

        verify(kernel).writeEffectiveConfigAsTransactionLog(second.resolve(ROLLBACK_SNAPSHOT_FILE));
        assertEquals(1, recordAttempt(mockArn + "-next"));
        // The interrupted move is completed rather than lost.
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
    }

    @Test
    void GIVEN_terminal_failure_recorded_for_exhausted_deployment_WHEN_launch_assessed_THEN_redelivery_refused()
            throws Exception {
        Path first = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(first);
        exhaustAttempts();
        deploymentDirectoryManager.recordLaunchRecovery(DeploymentDirectoryManager.LaunchRecovery.RESTORED);
        deploymentDirectoryManager.recordTerminalStatus(mockArn, DeploymentDirectoryManager.TerminalOutcome.FAILED);

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertFalse(assessment.isUnfinishedDeployment());
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
        assertEquals(first, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORED,
                deploymentDirectoryManager.findExhaustedFailure(mockArn).getLaunchRecovery());
    }

    @Test
    void GIVEN_no_ongoing_deployment_WHEN_terminal_status_recorded_THEN_nothing_written() throws Exception {
        deploymentDirectoryManager.recordTerminalStatus(mockArn, DeploymentDirectoryManager.TerminalOutcome.SUCCEEDED);

        assertEquals(Collections.emptyList(), fileNamesIn(deploymentsDir));
    }

    // ---- A failure that leaves unverified changes keeps the verified baseline ----

    @Test
    void GIVEN_failure_left_unverified_changes_WHEN_reported_THEN_kept_on_ongoing_link_and_snapshot_carried_next()
            throws Exception {
        Path failed = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(failed);
        recordAttempt();
        Files.write(failed.resolve(DEPLOYMENT_METADATA_FILE), "{}".getBytes(StandardCharsets.UTF_8));

        DeploymentDirectoryManager.TerminalOutcome outcome = deploymentDirectoryManager.recordTerminalStatus(mockArn,
                DeploymentDirectoryManager.TerminalOutcome.FAILED_UNVERIFIED);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, outcome);

        // Reported, so neither re-processed at launch nor restored, but its snapshot stays the baseline.
        assertEquals(failed, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertFalse(deploymentDirectoryManager.hasReadableDeploymentMetadata());
        assertFalse(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).isUnfinishedDeployment());
        assertEquals(failed, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));

        Path next = createNewDeploymentDir(SUPERSEDING_ARN);
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(next));
        // The reported failure's directory is kept as the last failed deployment's, as any reported failure's is.
        assertEquals(failed, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
    }

    @Test
    void GIVEN_failure_left_unverified_changes_but_snapshot_unusable_WHEN_reported_THEN_finalized(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path failed = createNewDeploymentDir(mockArn);
        Files.write(failed.resolve(ROLLBACK_SNAPSHOT_FILE), "not a transaction log".getBytes(StandardCharsets.UTF_8));
        recordAttempt();

        DeploymentDirectoryManager.TerminalOutcome outcome = deploymentDirectoryManager.recordTerminalStatus(mockArn,
                DeploymentDirectoryManager.TerminalOutcome.FAILED_UNVERIFIED);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, outcome);

        // Nothing worth carrying, so it is finished like any failure and cannot block the next deployment.
        assertEquals("FAILED", readRecord(failed).get("terminalOutcome"));
        assertEquals(failed, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertFalse(deploymentDirectoryManager.hasUnfinishedDeployment());
    }

    @Test
    void GIVEN_failure_left_unverified_changes_WHEN_same_deployment_redelivered_THEN_reprocessed_with_its_count()
            throws Exception {
        Path failed = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(failed);
        recordAttempt();
        DeploymentDirectoryManager.TerminalOutcome outcome = deploymentDirectoryManager.recordTerminalStatus(mockArn,
                DeploymentDirectoryManager.TerminalOutcome.FAILED_UNVERIFIED);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, outcome);

        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
        assertEquals(failed, createNewDeploymentDir(mockArn));

        assertNull(readRecord(failed).get("terminalOutcome"));
        assertEquals(2, recordAttempt());
    }

    @Test
    void GIVEN_exhausted_failure_left_unverified_changes_WHEN_redelivered_THEN_refused() throws Exception {
        writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        exhaustAttempts();
        DeploymentDirectoryManager.TerminalOutcome outcome = deploymentDirectoryManager.recordTerminalStatus(mockArn,
                DeploymentDirectoryManager.TerminalOutcome.FAILED_UNVERIFIED);
        deploymentDirectoryManager.persistLastFailedDeployment(mockArn, outcome);

        assertNotNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    // ---- A cancelled deployment is settled, but its snapshot is still the baseline ----

    @Test
    void GIVEN_cancelled_deployment_WHEN_launch_assessed_or_superseded_THEN_neither_reprocessed_nor_restored_but_carried()
            throws Exception {
        Path cancelled = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(cancelled);
        exhaustAttempts();
        Files.write(cancelled.resolve(DEPLOYMENT_METADATA_FILE), "{}".getBytes(StandardCharsets.UTF_8));
        // As DeploymentService records a cancel before publishing CANCELED.
        setTerminalOutcome(cancelled, "CANCELLED");

        assertFalse(deploymentDirectoryManager.hasReadableDeploymentMetadata());
        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);
        assertFalse(assessment.isUnfinishedDeployment());
        assertNull(assessment.getRecovery());
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
        assertEquals(cancelled, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));

        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(createNewDeploymentDir(SUPERSEDING_ARN)));
    }

    @Test
    void GIVEN_cancel_recorded_WHEN_same_deployment_redelivered_THEN_reprocessed_with_its_count() throws Exception {
        Path cancelled = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(cancelled);
        recordAttempt();

        assertEquals(DeploymentDirectoryManager.TerminalOutcome.CANCELLED, deploymentDirectoryManager
                .recordTerminalStatus(mockArn, DeploymentDirectoryManager.TerminalOutcome.CANCELLED));
        assertEquals("CANCELLED", readRecord(cancelled).get("terminalOutcome"));

        assertEquals(cancelled, createNewDeploymentDir(mockArn));
        assertNull(readRecord(cancelled).get("terminalOutcome"));
        assertEquals(2, recordAttempt());
    }

    @Test
    void GIVEN_cancelled_deployment_with_unusable_snapshot_WHEN_superseded_THEN_set_aside_still_cancelled(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        ignoreExceptionWithMessageSubstring(context, "is not a valid transaction log");
        Path cancelled = createNewDeploymentDir(mockArn);
        Files.write(cancelled.resolve(ROLLBACK_SNAPSHOT_FILE), "torn".getBytes(StandardCharsets.UTF_8));
        exhaustAttempts();
        deploymentDirectoryManager.recordTerminalStatus(mockArn, DeploymentDirectoryManager.TerminalOutcome.CANCELLED);

        assertThrows(DeploymentDirectoryManager.UnusableRollbackSnapshotException.class,
                () -> createNewDeploymentDir(SUPERSEDING_ARN));

        // Its cancel was reported, so it is not recorded as never reported, and was not failed, so not refused.
        assertEquals("CANCELLED", readRecord(cancelled).get("terminalOutcome"));
        assertEquals(cancelled, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertNull(deploymentDirectoryManager.findExhaustedFailure(mockArn));
    }

    // ---- The versions the rollback baseline needs ----

    @Test
    void GIVEN_rollback_snapshot_on_ongoing_link_WHEN_baseline_versions_read_THEN_version_and_previous_version_named()
            throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), ("{\"TS\":1,\"TP\":[\"services\",\"com.example.A\","
                + "\"version\"],\"W\":\"changed\",\"V\":\"1.5.0\"}\n{\"TS\":1,\"TP\":[\"services\","
                + "\"com.example.A\",\"previousVersion\"],\"W\":\"changed\",\"V\":\"1.4.1\"}\n{\"TS\":1,"
                + "\"TP\":[\"services\",\"com.example.B\",\"version\"],\"W\":\"changed\",\"V\":\"2.0.0\"}\n"
                + "{\"TS\":1,\"TP\":[\"services\",\"main\",\"lifecycle\",\"run\"],\"W\":\"changed\","
                + "\"V\":\"echo\"}\n").getBytes(StandardCharsets.UTF_8));

        Map<String, java.util.Set<String>> versions = deploymentDirectoryManager.findRollbackBaselineVersions();

        assertEquals(new java.util.HashSet<>(Arrays.asList("1.5.0", "1.4.1")), versions.get("com.example.A"));
        assertEquals(Collections.singleton("2.0.0"), versions.get("com.example.B"));
        assertFalse(versions.containsKey("main"));
    }

    @Test
    void GIVEN_no_usable_snapshot_WHEN_baseline_versions_read_THEN_nothing_named(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        assertEquals(Collections.emptyMap(), deploymentDirectoryManager.findRollbackBaselineVersions());
        Path dir = createNewDeploymentDir(mockArn);
        assertEquals(Collections.emptyMap(), deploymentDirectoryManager.findRollbackBaselineVersions());
        // Nothing can return the device to a snapshot that does not validate.
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), "torn".getBytes(StandardCharsets.UTF_8));
        assertEquals(Collections.emptyMap(), deploymentDirectoryManager.findRollbackBaselineVersions());
    }

    // ---- A record written by a newer nucleus ----

    @Test
    void GIVEN_launch_decision_this_version_does_not_know_WHEN_launch_assessed_THEN_not_decided_again()
            throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(dir);
        exhaustAttempts();
        Map<String, Object> record = readRecord(dir);
        record.put("launchRecovery", "RESTORED_BY_A_NEWER_NUCLEUS");
        writeRecord(dir, new ObjectMapper().writeValueAsString(record));

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        // Deciding again could restore a second time and erase whatever changed since the first decision.
        assertTrue(assessment.isUnfinishedDeployment());
        assertNull(assessment.getRecovery());
        assertNotNull(deploymentDirectoryManager.exhaustedProcessingState(mockArn).getLaunchRecovery());
    }

    @Test
    void GIVEN_terminal_outcome_this_version_does_not_know_WHEN_launch_assessed_or_superseded_THEN_settled_and_carried()
            throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(dir);
        exhaustAttempts();
        Files.write(dir.resolve(DEPLOYMENT_METADATA_FILE), "{}".getBytes(StandardCharsets.UTF_8));
        setTerminalOutcome(dir, "SETTLED_BY_A_NEWER_NUCLEUS");

        // Some terminal outcome was recorded, so it is not re-processed or restored as an unfinished one would be.
        assertFalse(deploymentDirectoryManager.hasReadableDeploymentMetadata());
        assertNull(deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false).getRecovery());
        // Not knowing what it left, the next deployment keeps its verified snapshot as the baseline.
        assertEquals(VERIFIED_SNAPSHOT, readSnapshot(createNewDeploymentDir(SUPERSEDING_ARN)));
    }

    // ---- Restoring config.tlog from a rollback snapshot at launch ----

    @Test
    void GIVEN_rollback_snapshot_WHEN_restored_THEN_transaction_log_replaced_and_previous_kept_as_backup()
            throws Exception {
        Path snapshot = writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        Path configDir = Files.createDirectories(deploymentsDir.resolve("config"));
        Path tlog = configDir.resolve("config.tlog");
        Files.write(tlog, "unverified".getBytes(StandardCharsets.UTF_8));
        // An interrupted truncation's leftover, which the launch would otherwise move back over the restore.
        Files.write(configDir.resolve("config.tlog.old"), "older".getBytes(StandardCharsets.UTF_8));

        assertTrue(deploymentDirectoryManager.restoreRollbackSnapshot(snapshot, tlog));

        assertEquals(VERIFIED_SNAPSHOT, read(tlog));
        // The leftover is settled the way the launch settles it, by moving it back over config.tlog, so nothing is
        // left to undo the restore and the replaced log kept as the backup is the one the launch would have loaded.
        assertEquals("older", read(configDir.resolve("config.tlog~")));
        assertEquals(Arrays.asList("config.tlog", "config.tlog~"), fileNamesIn(configDir));
        assertEquals(VERIFIED_SNAPSHOT, read(snapshot));
    }

    @Test
    // Windows moves a directory over a file, so the leftover below is only unmovable on POSIX. The rename-failure
    // test after this one covers the same path on every OS.
    @DisabledOnOs(OS.WINDOWS)
    void GIVEN_truncation_leftover_that_cannot_be_removed_WHEN_snapshot_restored_THEN_nothing_replaced(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, FileSystemException.class);
        ignoreExceptionOfType(context, DirectoryNotEmptyException.class);
        Path snapshot = writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        Path configDir = Files.createDirectories(deploymentsDir.resolve("config"));
        Path tlog = configDir.resolve("config.tlog");
        Files.write(tlog, "unverified".getBytes(StandardCharsets.UTF_8));
        // A leftover that can be neither deleted nor moved, for any user: the launch would move it back over a
        // restored config.tlog, undoing the restore while it is reported as done.
        Path leftover = Files.createDirectories(configDir.resolve("config.tlog.old").resolve("occupied"));

        assertFalse(deploymentDirectoryManager.restoreRollbackSnapshot(snapshot, tlog));

        assertEquals("unverified", read(tlog));
        assertThat(leftover.toFile(), anExistingDirectory());
        assertThat(configDir.resolve("config.tlog+").toFile(), not(anExistingFileOrDirectory()));
        assertEquals(VERIFIED_SNAPSHOT, read(snapshot));
    }

    @Test
    void GIVEN_truncation_leftover_cannot_be_moved_WHEN_snapshot_restored_THEN_nothing_replaced(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "The process cannot access the file");
        DeploymentDirectoryManager failingRename = spy(deploymentDirectoryManager);
        Path snapshot = writeVerifiedSnapshot(failingRename.createNewDeploymentDirectory(mockArn));
        Path configDir = Files.createDirectories(deploymentsDir.resolve("config"));
        Path tlog = configDir.resolve("config.tlog");
        Files.write(tlog, "unverified".getBytes(StandardCharsets.UTF_8));
        Path leftover = Files.write(configDir.resolve("config.tlog.old"), "older".getBytes(StandardCharsets.UTF_8));
        // Another process holds the leftover open, as an antivirus scanner can on Windows.
        doAnswer(invocation -> {
            if (leftover.equals(invocation.getArgument(0))) {
                throw new IOException("The process cannot access the file");
            }
            Files.move((Path) invocation.getArgument(0), (Path) invocation.getArgument(1),
                    StandardCopyOption.ATOMIC_MOVE);
            return null;
        }).when(failingRename).rename(any(), any(), anyBoolean());

        assertFalse(failingRename.restoreRollbackSnapshot(snapshot, tlog));

        assertEquals("unverified", read(tlog));
        assertEquals("older", read(leftover));
        assertEquals(Arrays.asList("config.tlog", "config.tlog.old"), fileNamesIn(configDir));
        assertEquals(VERIFIED_SNAPSHOT, read(snapshot));
    }

    @Test
    void GIVEN_disk_full_WHEN_snapshot_restored_THEN_transaction_log_untouched(ExtensionContext context)
            throws Exception {
        ignoreExceptionWithMessage(context, "No space left on device");
        DeploymentDirectoryManager failingCopy = spy(deploymentDirectoryManager);
        Path snapshot = writeVerifiedSnapshot(failingCopy.createNewDeploymentDirectory(mockArn));
        Path configDir = Files.createDirectories(deploymentsDir.resolve("config"));
        Path tlog = configDir.resolve("config.tlog");
        Files.write(tlog, "unverified".getBytes(StandardCharsets.UTF_8));
        doThrow(new IOException("No space left on device")).when(failingCopy).writeCarriedSnapshot(any(), any());

        assertFalse(failingCopy.restoreRollbackSnapshot(snapshot, tlog));

        assertEquals("unverified", read(tlog));
        assertEquals(Collections.singletonList("config.tlog"), fileNamesIn(configDir));
        assertEquals(VERIFIED_SNAPSHOT, read(snapshot));
    }

    @Test
    void GIVEN_restored_log_cannot_be_moved_into_place_WHEN_snapshot_restored_THEN_transaction_log_put_back(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Read-only file system");
        DeploymentDirectoryManager failingRename = spy(deploymentDirectoryManager);
        Path snapshot = writeVerifiedSnapshot(failingRename.createNewDeploymentDirectory(mockArn));
        Path configDir = Files.createDirectories(deploymentsDir.resolve("config"));
        Path tlog = configDir.resolve("config.tlog");
        Files.write(tlog, "unverified".getBytes(StandardCharsets.UTF_8));
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            if (String.valueOf(source.getFileName()).endsWith("+")) {
                throw new IOException("Read-only file system");
            }
            Files.move(source, (Path) invocation.getArgument(1), StandardCopyOption.ATOMIC_MOVE);
            return null;
        }).when(failingRename).rename(any(), any(), anyBoolean());

        assertFalse(failingRename.restoreRollbackSnapshot(snapshot, tlog));

        assertEquals("unverified", read(tlog));
        assertEquals(VERIFIED_SNAPSHOT, read(snapshot));
        assertThat(configDir.resolve("config.tlog+").toFile(), not(anExistingFileOrDirectory()));
    }

    // ---- What a launch does with an unfinished deployment ----

    @Test
    void GIVEN_no_unfinished_deployment_WHEN_launch_assessed_THEN_nothing_to_do() throws Exception {
        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertFalse(assessment.isUnfinishedDeployment());
        assertNull(assessment.getRecovery());
    }

    @Test
    void GIVEN_unfinished_deployment_with_attempts_left_WHEN_launch_assessed_THEN_unfinished_and_nothing_restored()
            throws Exception {
        writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        recordAttempt();

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertTrue(assessment.isUnfinishedDeployment());
        assertNull(assessment.getRecovery());
    }

    @Test
    void GIVEN_exhausted_deployment_that_rolls_back_WHEN_launch_assessed_THEN_restored_once() throws Exception {
        Path dir = createNewDeploymentDir(mockArn);
        writeVerifiedSnapshot(dir);
        exhaustAttempts();

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORED, assessment.getRecovery());
        assertEquals(dir.resolve(ROLLBACK_SNAPSHOT_FILE), assessment.getRollbackSnapshot());
        deploymentDirectoryManager.recordLaunchRecovery(assessment.getRecovery());

        // Restoring again would erase everything written since the first restore.
        DeploymentDirectoryManager.LaunchAssessment again =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);
        assertTrue(again.isUnfinishedDeployment());
        assertNull(again.getRecovery());
        // The decision travels with the record when the deployment is re-delivered, for its failure status.
        createNewDeploymentDir(mockArn);
        assertEquals(DeploymentDirectoryManager.LaunchRecovery.RESTORED,
                deploymentDirectoryManager.exhaustedProcessingState(mockArn).getLaunchRecovery());
    }

    @Test
    void GIVEN_exhausted_deployment_that_does_not_roll_back_WHEN_launch_assessed_THEN_not_restored()
            throws Exception {
        writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        for (int attempt = 0; attempt < DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS; attempt++) {
            deploymentDirectoryManager.recordProcessingAttempt(mockArn, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS,
                    false);
        }

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_NOT_REQUESTED, assessment.getRecovery());
        assertNull(assessment.getRollbackSnapshot());
    }

    @Test
    void GIVEN_exhausted_deployment_WHEN_launched_with_explicit_configuration_THEN_not_restored() throws Exception {
        writeVerifiedSnapshot(createNewDeploymentDir(mockArn));
        exhaustAttempts();

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(true);

        assertEquals(DeploymentDirectoryManager.LaunchRecovery.EXPLICIT_CONFIGURATION, assessment.getRecovery());
        assertNull(assessment.getRollbackSnapshot());
    }

    @Test
    void GIVEN_exhausted_deployment_with_invalid_snapshot_WHEN_launch_assessed_THEN_not_restored(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);
        Path dir = createNewDeploymentDir(mockArn);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), "\u0000\u0000 not a transaction log".getBytes(
                StandardCharsets.UTF_8));
        exhaustAttempts();

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE, assessment.getRecovery());
        assertNull(assessment.getRollbackSnapshot());
    }

    @Test
    void GIVEN_exhausted_deployment_without_snapshot_WHEN_launch_assessed_THEN_not_restored() throws Exception {
        createNewDeploymentDir(mockArn);
        exhaustAttempts();

        DeploymentDirectoryManager.LaunchAssessment assessment =
                deploymentDirectoryManager.assessUnfinishedDeploymentAtLaunch(false);

        assertEquals(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE, assessment.getRecovery());
    }

    private int recordAttempt() throws IOException {
        return recordAttempt(mockArn);
    }

    private int recordAttempt(String deploymentId) throws IOException {
        return deploymentDirectoryManager.recordProcessingAttempt(deploymentId,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);
    }

    private void exhaustAttempts() throws IOException {
        for (int attempt = 0; attempt < DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS; attempt++) {
            recordAttempt();
        }
    }

    /**
     * Make the mocked kernel dump the given content as the effective configuration.
     */
    private void kernelWritesSnapshot(String content) throws IOException {
        doAnswer(invocation -> {
            Files.write((Path) invocation.getArgument(0), content.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(kernel).writeEffectiveConfigAsTransactionLog(any());
    }

    /**
     * Make every rename over the ongoing link fail, as renaming a link over a link to a directory does on Windows.
     */
    private static void failRenamesOverTheOngoingLink(DeploymentDirectoryManager manager) throws IOException {
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            if (target.equals(manager.getOngoingDir()) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileSystemException(String.valueOf(target), null, "Access is denied");
            }
            Files.move((Path) invocation.getArgument(0), target);
            return null;
        }).when(manager).rename(any(), any(), anyBoolean());
    }

    private static Path writeVerifiedSnapshot(Path deploymentDir) throws IOException {
        return Files.write(deploymentDir.resolve(ROLLBACK_SNAPSHOT_FILE),
                VERIFIED_SNAPSHOT.getBytes(StandardCharsets.UTF_8));
    }

    private static String readSnapshot(Path deploymentDir) throws IOException {
        return read(deploymentDir.resolve(ROLLBACK_SNAPSHOT_FILE));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void writeRecord(Path deploymentDir, String json) throws IOException {
        Files.write(deploymentDir.resolve(PROCESSING_STATE_FILE), json.getBytes(StandardCharsets.UTF_8));
    }

    private static void setTerminalOutcome(Path deploymentDir, String outcome) throws IOException {
        Map<String, Object> record = readRecord(deploymentDir);
        record.put("terminalOutcome", outcome);
        writeRecord(deploymentDir, new ObjectMapper().writeValueAsString(record));
    }

    private static Map<String, Object> readRecord(Path deploymentDir) throws IOException {
        return readJson(deploymentDir.resolve(PROCESSING_STATE_FILE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Path file) throws IOException {
        return new ObjectMapper().readValue(file.toFile(), Map.class);
    }

    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> String.valueOf(p.getFileName())).sorted().collect(Collectors.toList());
        }
    }

    private static List<String> linkNamesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isSymbolicLink).map(p -> String.valueOf(p.getFileName())).sorted()
                    .collect(Collectors.toList());
        }
    }

    private Path createNewDeploymentDir(String arn) throws IOException {
        return deploymentDirectoryManager.createNewDeploymentDirectory(arn);
    }
}
