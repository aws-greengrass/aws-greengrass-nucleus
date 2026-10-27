/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Crashable;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.PROCESSING_STATE_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.ROLLBACK_SNAPSHOT_FILE;
import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_MEMBERSHIP_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_NAME_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessageSubstring;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeploymentService driving a real {@link DeploymentDirectoryManager} on disk, so the directory bookkeeping of an
 * unfinished deployment and of the deployments that follow it is checked end to end rather than against a mock.
 */
@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentServiceWithDeploymentDirectoryTest extends GGServiceTestUtil {
    private static final String UNFINISHED_ID = "unfinishedDeploymentId";
    private static final String TEST_DOCUMENT_ID = "testDeploymentId";
    private static final String LATER_ID = "laterDeploymentId";
    private static final String LOCAL_ID = "localDeploymentId";
    private static final String TEST_CONFIGURATION_ARN =
            "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final String VALID_SNAPSHOT =
            "{\"TS\":1602000000000,\"TP\":[\"services\",\"com.example.Component\",\"version\"],\"W\":\"changed\","
                    + "\"V\":\"1.0.0\"}\n";
    private static final long WAIT_MILLIS = 5000;

    @TempDir
    Path deploymentsDir;
    @Mock
    private ExecutorService executorService;
    @Mock
    private Kernel kernel;
    @Mock
    private DependencyResolver dependencyResolver;
    @Mock
    private ComponentManager componentManager;
    @Mock
    private KernelConfigResolver kernelConfigResolver;
    @Mock
    private DeploymentConfigMerger deploymentConfigMerger;
    @Mock
    private DeploymentStatusKeeper deploymentStatusKeeper;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private ThingGroupHelper thingGroupHelper;
    @Mock
    private NucleusPaths nucleusPaths;

    private DeploymentDirectoryManager directoryManager;
    private DeploymentService deploymentService;
    private DeploymentQueue deploymentQueue;
    private Thread deploymentServiceThread;

    @BeforeEach
    void setup() {
        serviceFullName = DEPLOYMENT_SERVICE_TOPICS;
        initializeMockedConfig();
        Topic pollingFrequency = Topic.of(context, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
        when(deviceConfiguration.getDeploymentPollingFrequencySeconds()).thenReturn(pollingFrequency);
        when(context.get(IotJobsHelper.class)).thenReturn(mock(IotJobsHelper.class));
        Topics groupToLastDeploymentTopics = Topics.of(context, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null);
        lenient().when(config.lookupTopics(eq(GROUP_TO_LAST_DEPLOYMENT_TOPICS), anyString()))
                .thenReturn(groupToLastDeploymentTopics);

        doReturn(deploymentsDir).when(nucleusPaths).deploymentPath();
        directoryManager = spy(new DeploymentDirectoryManager(kernel, nucleusPaths));
        deploymentService = new DeploymentService(config, executorService, dependencyResolver, componentManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, directoryManager, context,
                kernel, deviceConfiguration, thingGroupHelper);
        deploymentService.postInject();
        deploymentQueue = new DeploymentQueue();
        deploymentService.setDeploymentsQueue(deploymentQueue);
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        deploymentService.shutdown();
        if (deploymentServiceThread != null) {
            deploymentServiceThread.interrupt();
            deploymentServiceThread.join(5000);
        }
    }

    @Test
    void GIVEN_superseding_deployment_fails_before_link_switch_THEN_unfinished_deployment_untouched(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        Path unfinished = stageUnfinishedDeployment(UNFINISHED_ID, VALID_SNAPSHOT, 1);
        // Transient: the unfinished deployment's snapshot is fine, but it cannot be copied right now.
        doThrow(new IOException("No space left on device")).when(directoryManager).writeCarriedSnapshot(any(),
                any());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-B"));
        startDeploymentService();

        awaitStatus("job-B", JobStatus.FAILED);
        verify(directoryManager, timeout(WAIT_MILLIS)).persistLastFailedDeployment(eq(TEST_DOCUMENT_ID), any());
        // The verify passes once the call starts; wait for the link it moves before reading it.
        awaitCurrentDeploymentFinished();
        // The failure is the superseding deployment's alone: the unfinished one was never reported, keeps its
        // attempt and its snapshot, and stays on the ongoing link for the next deployment to carry.
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getOngoingDir()));
        Map<String, Object> record = readRecord(unfinished);
        assertNull(record.get("terminalOutcome"));
        assertEquals(1, record.get("processingAttempts"));
        assertEquals(VALID_SNAPSHOT, read(unfinished.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertFalse(Files.exists(directoryManager.getPreviousFailureDir(), LinkOption.NOFOLLOW_LINKS));
        assertNull(directoryManager.findExhaustedFailure(UNFINISHED_ID));
    }

    @Test
    void GIVEN_superseding_deployment_fails_before_link_switch_WHEN_next_deployment_arrives_THEN_it_carries_the_snapshot(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        stageUnfinishedDeployment(UNFINISHED_ID, VALID_SNAPSHOT, 1);
        // Only the first carry fails, as a full disk that is then cleaned up would.
        doThrow(new IOException("No space left on device")).doCallRealMethod().when(directoryManager)
                .writeCarriedSnapshot(any(), any());
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-B"));
        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        startDeploymentService();

        awaitStatus("job-B", JobStatus.FAILED);
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path ongoing = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(ongoing.endsWith(LATER_ID));
        // The unfinished deployment's verified snapshot, not live configuration, is the later deployment's baseline.
        assertEquals(VALID_SNAPSHOT, read(ongoing.resolve(ROLLBACK_SNAPSHOT_FILE)));
    }

    @Test
    void GIVEN_unfinished_snapshot_corrupt_WHEN_superseded_THEN_fails_once_set_aside_and_later_deployment_proceeds(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, JsonParseException.class);
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "not a valid transaction log");
        Path unfinished = stageUnfinishedDeployment(UNFINISHED_ID, "not a transaction log", 1);
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-B"));
        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-B", JobStatus.FAILED);
        assertEquals(DeploymentStatus.FAILED_NO_STATE_CHANGE.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        assertThat(String.valueOf(details.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                containsString("That deployment was set aside"));
        // The deployment after it is not blocked by the corrupt snapshot.
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        assertTrue(Files.readSymbolicLink(directoryManager.getOngoingDir()).endsWith(LATER_ID));
        // Set aside on purpose and kept as evidence, not recorded as a failure that was reported.
        assertEquals("ABANDONED", readRecord(unfinished).get("terminalOutcome"));
        assertEquals("not a transaction log", read(unfinished.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
    }

    @Test
    void GIVEN_reprocessed_deployment_fails_before_activation_THEN_status_says_state_changed_and_baseline_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "download failed");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT, 1);
        CompletableFuture<DeploymentResult> failedBeforeActivation = CompletableFuture.completedFuture(
                new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, new IOException("download failed")));
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(failedBeforeActivation,
                new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        // The first attempt merged its configuration, so "no state change" would be false.
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        verify(directoryManager, timeout(WAIT_MILLIS)).persistLastFailedDeployment(eq(TEST_DOCUMENT_ID), any());
        // The verify passes once the call starts; wait for the link it moves before reading it.
        awaitCurrentDeploymentFinished();
        assertEquals("FAILED_UNVERIFIED", readRecord(unfinished).get("terminalOutcome"));

        deploymentQueue.offer(deployment(LATER_ID, "job-B"));
        verify(executorService, timeout(WAIT_MILLIS).times(2)).submit(any(DefaultDeploymentTask.class));
        Path next = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertNotEquals(unfinished, next);
        // The verified baseline is carried on to the next deployment rather than dropped.
        assertEquals(VALID_SNAPSHOT, read(next.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
    }

    @Test
    void GIVEN_fresh_deployment_fails_before_activation_THEN_no_state_change_and_finalized(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "download failed");
        CompletableFuture<DeploymentResult> failedBeforeActivation = CompletableFuture.completedFuture(
                new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, new IOException("download failed")));
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(failedBeforeActivation);

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        // Main's behavior: nothing was applied, so nothing changed and nothing needs carrying.
        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        assertEquals(DeploymentStatus.FAILED_NO_STATE_CHANGE.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        verify(directoryManager, timeout(WAIT_MILLIS)).persistLastFailedDeployment(eq(TEST_DOCUMENT_ID), any());
        // The verify passes once the call starts; wait for the link it moves before reading it.
        awaitCurrentDeploymentFinished();
        assertFalse(Files.exists(directoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.readSymbolicLink(directoryManager.getPreviousFailureDir()).endsWith(TEST_DOCUMENT_ID));
    }

    @Test
    void GIVEN_same_deployment_redelivered_with_corrupt_snapshot_THEN_same_deployment_wording_and_finalized(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, JsonParseException.class);
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "transaction log");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, "not a transaction log", 1);

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        assertThat(String.valueOf(details.get(DEPLOYMENT_FAILURE_CAUSE_KEY)), containsString(
                "The rollback snapshot this deployment kept from an earlier delivery is not usable, so it was not "
                        + "applied again"));
        // An earlier attempt applied it and nothing can roll that back.
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        // Reported, so finished; with nothing usable to carry it does not stay on the ongoing link.
        verify(directoryManager, timeout(WAIT_MILLIS)).persistLastFailedDeployment(eq(TEST_DOCUMENT_ID), any());
        // The verify passes once the call starts; wait for the link it moves before reading it.
        awaitCurrentDeploymentFinished();
        assertEquals("FAILED", readRecord(unfinished).get("terminalOutcome"));
        assertFalse(Files.exists(directoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void GIVEN_same_deployment_exhausted_with_corrupt_snapshot_THEN_exhaustion_reported(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "on every allowed attempt");
        stageUnfinishedDeployment(TEST_DOCUMENT_ID, "not a transaction log", DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        directoryManager.recordLaunchRecovery(DeploymentDirectoryManager.LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE);

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        assertThat(String.valueOf(details.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                containsString("Its rollback configuration was missing or invalid"));
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
    }

    @Test
    void GIVEN_exhausted_deployment_restored_at_launch_WHEN_redelivered_twice_THEN_failed_then_rejected(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "on every allowed attempt");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        directoryManager.recordLaunchRecovery(DeploymentDirectoryManager.LaunchRecovery.RESTORED);

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        assertEquals(DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        verify(directoryManager, timeout(WAIT_MILLIS)).persistLastFailedDeployment(eq(TEST_DOCUMENT_ID), any());
        // The verify passes once the call starts; wait for the link it moves before reading it.
        awaitCurrentDeploymentFinished();
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A-again"));
        Map<String, Object> rejected = awaitStatus("job-A-again", JobStatus.REJECTED);
        assertThat(String.valueOf(rejected.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                containsString("the device has been restored to the configuration from before it"));
    }

    // ---- A failure while the deployment directory is prepared keeps what that directory now holds ----

    @Test
    void GIVEN_superseding_deployment_WHEN_its_metadata_cannot_be_written_THEN_carried_baseline_kept_for_next_deployment(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        stageUnfinishedDeployment(UNFINISHED_ID, VALID_SNAPSHOT, 1);
        // The disk fills up once the unfinished deployment's snapshot is carried into the new directory and the
        // ongoing link has moved to it.
        doThrow(new IOException("No space left on device")).doCallRealMethod().when(directoryManager)
                .writeDeploymentMetadata(any());
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-B"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-B", JobStatus.FAILED);
        // The superseding deployment applied nothing itself.
        assertEquals(DeploymentStatus.FAILED_NO_STATE_CHANGE.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        awaitCurrentDeploymentFinished();
        // Its directory holds the unfinished deployment's verified snapshot, and live configuration may still hold
        // that deployment's unverified changes, so it stays the baseline rather than being finalized.
        Path superseding = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(superseding.endsWith(TEST_DOCUMENT_ID));
        assertEquals("FAILED_UNVERIFIED", readRecord(superseding).get("terminalOutcome"));
        assertEquals(VALID_SNAPSHOT, read(superseding.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertFalse(Files.exists(directoryManager.getPreviousFailureDir(), LinkOption.NOFOLLOW_LINKS));

        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        // The later deployment carries the verified snapshot instead of snapshotting live configuration.
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertEquals(superseding, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
    }

    @Test
    void GIVEN_redelivered_deployment_WHEN_its_metadata_cannot_be_written_THEN_unable_to_roll_back_and_baseline_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT, 1);
        doThrow(new IOException("No space left on device")).doCallRealMethod().when(directoryManager)
                .writeDeploymentMetadata(any());
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        // Its first attempt merged its configuration, and this delivery failed before it could undo that.
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        awaitCurrentDeploymentFinished();
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getOngoingDir()));
        Map<String, Object> record = readRecord(unfinished);
        assertEquals("FAILED_UNVERIFIED", record.get("terminalOutcome"));
        assertEquals(1, record.get("processingAttempts"));
        assertEquals(VALID_SNAPSHOT, read(unfinished.resolve(ROLLBACK_SNAPSHOT_FILE)));

        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
    }

    @Test
    void GIVEN_exhausted_redelivery_not_restored_WHEN_metadata_cannot_be_written_THEN_launch_decision_reported_and_baseline_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "on every allowed attempt");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        directoryManager.recordLaunchRecovery(DeploymentDirectoryManager.LaunchRecovery.RESTORE_FAILED);
        lenient().doThrow(new IOException("No space left on device")).when(directoryManager)
                .writeDeploymentMetadata(any());
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        // What the launch decided, not "nothing changed": the restore failed, so the device keeps unverified changes.
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        assertThat(String.valueOf(details.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                containsString("Restoring the configuration from before the deployment failed"));
        awaitCurrentDeploymentFinished();
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getOngoingDir()));
        assertEquals("FAILED_UNVERIFIED", readRecord(unfinished).get("terminalOutcome"));

        // Disk space is freed before the next deployment arrives.
        doCallRealMethod().when(directoryManager).writeDeploymentMetadata(any());
        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
    }

    @Test
    void GIVEN_exhausted_redelivery_restored_WHEN_metadata_cannot_be_written_THEN_rollback_complete_reported(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "on every allowed attempt");
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS);
        directoryManager.recordLaunchRecovery(DeploymentDirectoryManager.LaunchRecovery.RESTORED);
        lenient().doThrow(new IOException("No space left on device")).when(directoryManager)
                .writeDeploymentMetadata(any());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        assertEquals(DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        assertThat(String.valueOf(details.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                containsString("The device has been restored to the configuration from before the deployment"));
        awaitCurrentDeploymentFinished();
        // Restored, so live configuration is the verified baseline again and nothing needs carrying.
        assertEquals("FAILED", readRecord(unfinished).get("terminalOutcome"));
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
        assertFalse(Files.exists(directoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void GIVEN_superseding_deployment_WHEN_metadata_and_terminal_record_cannot_be_written_THEN_baseline_still_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "No space left on device");
        ignoreExceptionOfType(extContext, DirectoryNotEmptyException.class);
        stageUnfinishedDeployment(UNFINISHED_ID, VALID_SNAPSHOT, 1);
        doAnswer(invocation -> {
            // From here on nothing more can be written to the deployment directory, the terminal record included.
            // A directory where the record's new version goes fails that write for any user, root included.
            Path dir = Files.readSymbolicLink(directoryManager.getOngoingDir());
            Files.createDirectories(dir.resolve(PROCESSING_STATE_FILE + "+").resolve("occupied"));
            throw new IOException("No space left on device");
        }).doCallRealMethod().when(directoryManager).writeDeploymentMetadata(any());
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-B"));
        startDeploymentService();

        awaitStatus("job-B", JobStatus.FAILED);
        awaitCurrentDeploymentFinished();
        // Keeping the baseline is decided by the outcome DeploymentService computed, not by reading back a record
        // whose write failed on the same full disk.
        Path superseding = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(superseding.endsWith(TEST_DOCUMENT_ID));
        assertNull(readRecord(superseding).get("terminalOutcome"));
        assertEquals(VALID_SNAPSHOT, read(superseding.resolve(ROLLBACK_SNAPSHOT_FILE)));

        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
    }

    @Test
    void GIVEN_reported_failure_redelivered_WHEN_its_directory_cannot_be_reused_THEN_unable_to_roll_back_and_baseline_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseOfType(extContext, DirectoryNotEmptyException.class);
        // Reported failed after its first attempt applied changes, and kept on the ongoing link as the baseline.
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT, 1);
        directoryManager.recordTerminalStatus(TEST_DOCUMENT_ID,
                DeploymentDirectoryManager.TerminalOutcome.FAILED_UNVERIFIED);
        // Re-processing it clears that outcome, and nothing can be written to its record any more. A directory where
        // the record's new version goes fails the write for any user, root included.
        Path occupied = Files.createDirectories(unfinished.resolve(PROCESSING_STATE_FILE + "+").resolve("occupied"));
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(new CompletableFuture<>());

        deploymentQueue.offer(deployment(TEST_DOCUMENT_ID, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        // What its directory held when the failure happened decides the status, not a fresh-looking state.
        assertEquals(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK.name(), details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        awaitCurrentDeploymentFinished();
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getOngoingDir()));
        assertEquals(1, readRecord(unfinished).get("processingAttempts"));
        assertEquals(VALID_SNAPSHOT, read(unfinished.resolve(ROLLBACK_SNAPSHOT_FILE)));

        Files.delete(occupied);
        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
    }

    // ---- A cancelled deployment is settled: never re-processed by a launch, but still the baseline ----

    @Test
    void GIVEN_local_deployment_cancelled_WHEN_cancel_published_THEN_recorded_first_so_a_launch_does_not_reprocess_it()
            throws Exception {
        lenient().when(config.lookupTopics(eq(GROUP_TO_ROOT_COMPONENTS_TOPICS), anyString()))
                .thenReturn(Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null));
        CompletableFuture<DeploymentResult> running = new CompletableFuture<>();
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(running,
                new CompletableFuture<>());
        // What a launch would find had the nucleus stopped right after CANCELED was published.
        AtomicBoolean reprocessableWhenPublished = new AtomicBoolean(true);
        // Lenient: the same method also publishes IN_PROGRESS, with other arguments.
        lenient().doAnswer(invocation -> {
            reprocessableWhenPublished.set(directoryManager.hasReadableDeploymentMetadata());
            return null;
        }).when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(LOCAL_ID), any(), any(), any(),
                eq(JobStatus.CANCELED.toString()), any(), any());

        deploymentQueue.offer(localDeployment(LOCAL_ID));
        startDeploymentService();
        verify(executorService, timeout(WAIT_MILLIS)).submit(any(DefaultDeploymentTask.class));
        // Its activation took the rollback snapshot and counted the attempt; the merge may have run too.
        Path cancelled = Files.readSymbolicLink(directoryManager.getOngoingDir());
        Files.write(cancelled.resolve(ROLLBACK_SNAPSHOT_FILE), VALID_SNAPSHOT.getBytes(StandardCharsets.UTF_8));
        directoryManager.recordProcessingAttempt(LOCAL_ID, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);

        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.LOCAL, LOCAL_ID, true));

        verify(deploymentStatusKeeper, timeout(WAIT_MILLIS)).persistAndPublishDeploymentStatus(eq(LOCAL_ID), any(),
                any(), any(), eq(JobStatus.CANCELED.toString()), any(), any());
        assertTrue(running.isCancelled());
        assertFalse(reprocessableWhenPublished.get(),
                "a launch after CANCELED was published would re-apply the cancelled deployment");
        // The cancel may have landed after the merge, so its snapshot stays the baseline for the next deployment.
        assertEquals(cancelled, Files.readSymbolicLink(directoryManager.getOngoingDir()));
        assertEquals(1, readRecord(cancelled).get("processingAttempts"));

        deploymentQueue.offer(deployment(LATER_ID, "job-C"));
        verify(executorService, timeout(WAIT_MILLIS).times(2)).submit(any(DefaultDeploymentTask.class));
        Path later = Files.readSymbolicLink(directoryManager.getOngoingDir());
        assertTrue(later.endsWith(LATER_ID));
        assertEquals(VALID_SNAPSHOT, read(later.resolve(ROLLBACK_SNAPSHOT_FILE)));
    }

    // ---- A re-processed deployment whose failure handling keeps its changes ----

    @Test
    void GIVEN_reprocessed_do_nothing_deployment_fails_before_activation_THEN_rollback_not_requested_and_roots_kept(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(extContext, "download failed");
        // Recording the group's root components runs on the publish queue.
        doAnswer(invocation -> {
            ((Crashable) invocation.getArgument(0)).run();
            return null;
        }).when(context).runOnPublishQueueAndWait(any());
        Topics groupToRootComponents = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(groupToRootComponents);
        when(config.lookupTopics(GROUP_TO_LAST_DEPLOYMENT_TOPICS))
                .thenReturn(Topics.of(context, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null));
        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(Topics.of(context, GROUP_MEMBERSHIP_TOPICS,
                null));
        when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(mock(Topics.class));
        GreengrassService rootService = mock(GreengrassService.class);
        when(rootService.getName()).thenReturn("component1");
        when(rootService.getDependencies()).thenReturn(Collections.emptyMap());
        when(kernel.locate("component1")).thenReturn(rootService);
        // An earlier attempt of this DO_NOTHING deployment merged its configuration, then the nucleus stopped.
        Path unfinished = stageUnfinishedDeployment(TEST_DOCUMENT_ID, VALID_SNAPSHOT, 1, false);
        when(executorService.submit(any(DefaultDeploymentTask.class))).thenReturn(CompletableFuture.completedFuture(
                new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, new IOException("download failed"))));

        deploymentQueue.offer(new Deployment(testDocument(TEST_DOCUMENT_ID).replace("\"componentUpdatePolicy\"",
                "\"failureHandlingPolicy\": \"DO_NOTHING\", \"componentUpdatePolicy\""),
                Deployment.DeploymentType.IOT_JOBS, "job-A"));
        startDeploymentService();

        Map<String, Object> details = awaitStatus("job-A", JobStatus.FAILED);
        // Its policy keeps what the earlier attempt applied, so that is what the device runs now.
        assertEquals(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED.name(),
                details.get(DEPLOYMENT_DETAILED_STATUS_KEY));
        awaitCurrentDeploymentFinished();
        // The group's root components are those the device runs, so later deployments for other groups keep them.
        Topics groupRoots = groupToRootComponents.findTopics("thinggroup/group1", "component1");
        assertNotNull(groupRoots, groupToRootComponents::toString);
        assertEquals("1.0.0", Coerce.toString(groupRoots.find(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY)));
        // The kept changes are the device's configuration now, so nothing is carried: it is finished like any failure.
        assertEquals("FAILED", readRecord(unfinished).get("terminalOutcome"));
        assertEquals(unfinished, Files.readSymbolicLink(directoryManager.getPreviousFailureDir()));
        assertFalse(Files.exists(directoryManager.getOngoingDir(), LinkOption.NOFOLLOW_LINKS));
    }

    private Path stageUnfinishedDeployment(String deploymentId, String snapshot, int attempts) throws IOException {
        return stageUnfinishedDeployment(deploymentId, snapshot, attempts, true);
    }

    private Path stageUnfinishedDeployment(String deploymentId, String snapshot, int attempts,
                                           boolean rollbackOnFailure) throws IOException {
        Path dir = directoryManager.createNewDeploymentDirectory(deploymentId);
        Files.write(dir.resolve(ROLLBACK_SNAPSHOT_FILE), snapshot.getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < attempts; attempt++) {
            directoryManager.recordProcessingAttempt(deploymentId, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS,
                    rollbackOnFailure);
        }
        return dir;
    }

    private Deployment deployment(String deploymentId, String jobId) {
        return new Deployment(testDocument(deploymentId), Deployment.DeploymentType.IOT_JOBS, jobId);
    }

    private static Deployment localDeployment(String requestId) {
        return new Deployment("{\"requestId\":\"" + requestId + "\",\"requestTimestamp\":1602000000000,"
                + "\"componentsToMerge\":{\"component1\":\"1.0.0\"}}", Deployment.DeploymentType.LOCAL, requestId);
    }

    /**
     * Wait until DeploymentService has finished the current deployment's bookkeeping, which it completes after
     * publishing the terminal status.
     */
    private void awaitCurrentDeploymentFinished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (deploymentService.getCurrentDeploymentTaskMetadata() != null
                && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNull(deploymentService.getCurrentDeploymentTaskMetadata(), "the current deployment did not finish");
    }

    private String testDocument(String deploymentId) {
        String document;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))) {
            document = reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return document.replace(TEST_DOCUMENT_ID, deploymentId)
                .replace("TARGET_CONFIGURATION_ARN", TEST_CONFIGURATION_ARN);
    }

    private Map<String, Object> awaitStatus(String jobId, JobStatus status) {
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(deploymentStatusKeeper, timeout(WAIT_MILLIS)).persistAndPublishDeploymentStatus(eq(jobId), any(),
                any(), any(), eq(status.toString()), details.capture(), any());
        return details.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readRecord(Path deploymentDir) throws IOException {
        return new ObjectMapper().readValue(deploymentDir.resolve(PROCESSING_STATE_FILE).toFile(), Map.class);
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void startDeploymentService() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getContexts() != null && DEPLOYMENT_SERVICE_TOPICS.equalsIgnoreCase(
                    m.getContexts().get(SERVICE_NAME_KEY))) {
                running.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        deploymentServiceThread = new Thread(() -> {
            try {
                deploymentService.startup();
            } catch (InterruptedException ignore) {
            }
        });
        deploymentServiceThread.start();
        boolean started = running.await(1, TimeUnit.SECONDS);
        Slf4jLogAdapter.removeGlobalListener(listener);
        assertTrue(started, "Deployment service must be running");
    }
}
