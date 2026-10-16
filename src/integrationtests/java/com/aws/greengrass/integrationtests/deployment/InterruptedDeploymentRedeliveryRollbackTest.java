/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.ShadowDeploymentListener;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.DeploymentTaskMetadata;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.CONFIGURATION_ARN_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.GG_DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.status.model.DeploymentInformation.ARN_FOR_STATUS_KEY;
import static com.aws.greengrass.status.model.DeploymentInformation.STATUS_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the interrupted redelivery scenario that aws-greengrass-nucleus PR #1838 addresses, as an integration test
 * over one persistent Greengrass root that two Nucleus instances run on in turn.
 *
 * <p>com.example.RollbackRepro runs the known-good 01.05.00 (01.04.01 before it). A thing (shadow) deployment moves
 * it to the faulty 01.05.22.84, which declares no bootstrap step, so DefaultActivator applies it. The Nucleus is
 * interrupted after the merge has made 01.05.22.84 durable but before the deployment reaches a terminal status. The
 * restarted Nucleus launches in the DEFAULT stage, and the cloud then re-delivers the same deployment, as the shadow
 * listener does after an MQTT reconnect while the reported status is still IN_PROGRESS. The faulty version fails and
 * the deployment rolls back.
 *
 * <p>Boundaries not crossed: the interruption is a graceful in-JVM Kernel shutdown rather than a process kill or
 * power loss, and the re-delivered shadow document is handed to ShadowDeploymentListener's shadow handler directly
 * instead of arriving over MQTT. Everything from the shadow handler down to the deployment directory, config.tlog,
 * component lifecycle and rollback is the production code path.
 */
class InterruptedDeploymentRedeliveryRollbackTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(InterruptedDeploymentRedeliveryRollbackTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String EVIDENCE = "[PR1838-REPRO] ";

    private static final String COMPONENT = "com.example.RollbackRepro";
    private static final String OLDER_GOOD_VERSION = "01.04.01";
    private static final String KNOWN_GOOD_VERSION = "01.05.00";
    // A four-part version string, 01.05.22.84. Built rather than written out because PMD's
    // AvoidUsingHardCodedIP reads a four-part dotted literal as an IPv4 address.
    private static final String FAULTY_VERSION = String.format("%02d.%02d.%02d.%02d", 1, 5, 22, 84);
    private static final String ARTIFACT_FILE = "rollbackRepro.txt";
    // Synthetic thing deployment ARN: example account, no customer identifiers.
    private static final String THING_CONFIGURATION_ARN_PREFIX =
            "arn:aws:greengrass:us-east-1:123456789012:configuration:thing/RollbackReproThing:";

    // On-disk deployment layout, identical on both sides of the PR.
    private static final String ONGOING_LINK = "ongoing";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";
    private static final String DEPLOYMENT_METADATA_FILE = "deployment_metadata.json";
    private static final String BOOTSTRAP_TASK_FILE = "bootstrap_task.json";
    private static final String TARGET_CONFIG_FILE = "target_config.tlog";
    private static final String CONFIG_TLOG_FILE = "config.tlog";

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String FAILED_ROLLBACK_COMPLETE = "FAILED_ROLLBACK_COMPLETE";
    private static final List<String> TERMINAL_STATUSES = Collections.unmodifiableList(
            Arrays.asList(SUCCEEDED, FAILED, "REJECTED", "CANCELED"));

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REDELIVERY_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration NO_REQUEUE_WINDOW = Duration.ofSeconds(3);

    private Kernel kernel;
    private Consumer<GreengrassLogMessage> interruptionWatcher;

    @AfterEach
    void after() {
        if (interruptionWatcher != null) {
            Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_component_only_deployment_interrupted_after_merge_WHEN_cloud_redelivers_it_after_restart_THEN_rollback_restores_known_good_version(
            ExtensionContext context) throws Exception {
        // The faulty version breaking, and the interruption landing inside activation, are the scenario itself.
        // ExceptionLogProtector cannot withdraw an allowance and the shutdown-time sites that log an interrupted
        // cause are not fixed, so the interruption allowance is test-wide; run 2 is watched for one separately.
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);

        long creationTimestamp = System.currentTimeMillis();
        DeploymentStep olderGood = DeploymentStep.of(563, OLDER_GOOD_VERSION, creationTimestamp);
        DeploymentStep knownGood = DeploymentStep.of(564, KNOWN_GOOD_VERSION, creationTimestamp + 1);
        DeploymentStep faulty = DeploymentStep.of(565, FAULTY_VERSION, creationTimestamp + 2);

        // ---------------- Nucleus run 1: reach 01.05.00, then interrupt the faulty deployment ----------------
        kernel = launchFirstNucleus();
        StatusRecorder run1 = StatusRecorder.registerOn(kernel);

        // Offline, so each target version is staged in the component store as the cloud download would leave it.
        stageInComponentStore(kernel, OLDER_GOOD_VERSION);
        deliverThroughShadow(kernel, olderGood, null, 1);
        assertTrue(waitFor(() -> run1.statusesOf(olderGood).contains(SUCCEEDED), STEP_TIMEOUT),
                () -> run1.describe(olderGood));
        stageInComponentStore(kernel, KNOWN_GOOD_VERSION);
        deliverThroughShadow(kernel, knownGood, null, 2);
        assertTrue(waitFor(() -> run1.statusesOf(knownGood).contains(SUCCEEDED), STEP_TIMEOUT),
                () -> run1.describe(knownGood));
        GreengrassService component = kernel.locate(COMPONENT);
        assertTrue(waitFor(() -> State.RUNNING.equals(component.getState()), STEP_TIMEOUT));
        assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY));
        assertEquals(OLDER_GOOD_VERSION, liveValue(kernel, PREV_VERSION_CONFIG_KEY));

        stageInComponentStore(kernel, FAULTY_VERSION);
        deliverThroughShadow(kernel, faulty, null, 3);
        // Wait until DefaultActivator has merged the faulty version and the component is starting it. The faulty
        // startup step takes 5 seconds to fail, so activation cannot reach a result before the interruption below.
        assertTrue(waitFor(() -> FAULTY_VERSION.equals(liveValue(kernel, VERSION_CONFIG_KEY)), STEP_TIMEOUT));
        assertTrue(waitFor(() -> State.STARTING.equals(component.getState()), STEP_TIMEOUT));
        kernel.getContext().waitForPublishQueueToClear();

        Path deploymentsDir = kernel.getNucleusPaths().deploymentPath();
        Path ongoingLink = deploymentsDir.resolve(ONGOING_LINK);
        Path previousFailureLink = deploymentsDir.resolve(PREVIOUS_FAILURE_LINK);
        Path attemptOneDir = Files.readSymbolicLink(ongoingLink).toAbsolutePath();
        Map<String, String> attemptOneSnapshot = recordedVersions(attemptOneDir.resolve(ROLLBACK_SNAPSHOT_FILE));
        logger.atInfo().kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY))
                .kv("livePreviousVersion", liveValue(kernel, PREV_VERSION_CONFIG_KEY))
                .kv("componentState", component.getState()).kv("deploymentDir", attemptOneDir)
                .kv("snapshot", attemptOneSnapshot).kv("statuses", run1.statusesOf(faulty))
                .log(EVIDENCE + "before interruption");

        // Assertion 1: the faulty target is the live state, and the rollback point holds the known-good version.
        assertEquals(FAULTY_VERSION, liveValue(kernel, VERSION_CONFIG_KEY));
        assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, PREV_VERSION_CONFIG_KEY));
        assertEquals(faulty.safeDirectoryName(), String.valueOf(attemptOneDir.getFileName()));
        assertEquals(KNOWN_GOOD_VERSION, attemptOneSnapshot.get(VERSION_CONFIG_KEY));
        assertEquals(OLDER_GOOD_VERSION, attemptOneSnapshot.get(PREV_VERSION_CONFIG_KEY));
        assertThat(run1.statusesOf(faulty), contains(IN_PROGRESS));
        // Component-only deployment: the restart-based path's files are absent, so DefaultActivator is applying it.
        assertFalse(Files.exists(attemptOneDir.resolve(BOOTSTRAP_TASK_FILE)));
        assertFalse(Files.exists(attemptOneDir.resolve(TARGET_CONFIG_FILE)));

        // Interruption. DeploymentService stops polling as soon as it is closed, so the activation still waiting
        // on the faulty component never gets a terminal status reported and the ongoing link stays in place.
        Path rootDir = kernel.getNucleusPaths().rootPath();
        Path configTlog = kernel.getNucleusPaths().configPath().resolve(CONFIG_TLOG_FILE);
        Path kernelAltsDir = kernel.getNucleusPaths().kernelAltsPath();
        kernel.shutdown();

        Map<String, String> durableConfig = recordedVersions(configTlog);
        logger.atInfo().kv("root", rootDir).kv("configTlog", durableConfig)
                .kv("ongoingTarget", linkTarget(ongoingLink))
                .kv("statuses", run1.statusesOf(faulty)).log(EVIDENCE + "after interruption, on disk");
        // The unverified version is durable, nothing terminal was reported, and nothing can resume it: no
        // kernel-alts state exists, so the next launch is in the DEFAULT stage.
        assertEquals(FAULTY_VERSION, durableConfig.get(VERSION_CONFIG_KEY));
        assertThat(run1.statusesOf(faulty), contains(IN_PROGRESS));
        assertTrue(Files.isSymbolicLink(ongoingLink));
        assertEquals(attemptOneDir, Files.readSymbolicLink(ongoingLink).toAbsolutePath());
        assertTrue(Files.exists(attemptOneDir.resolve(DEPLOYMENT_METADATA_FILE)));
        assertFalse(Files.exists(kernelAltsDir.resolve("old")));
        assertFalse(Files.exists(kernelAltsDir.resolve("broken")));

        // ---------------- Nucleus run 2: restart on the same root, then the cloud re-delivers ----------------
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        // Every logged cause rooted in an InterruptedException from here on: the allowance above is for run 1's
        // interruption, and must not hide one in run 2.
        List<String> runTwoInterruptions = new CopyOnWriteArrayList<>();
        interruptionWatcher = message -> {
            if (message.getCause() != null
                    && Utils.getUltimateCause(message.getCause()) instanceof InterruptedException) {
                runTwoInterruptions.add(message.getThread() + " " + message.getLoggerName() + ": "
                        + message.getMessage());
            }
        };
        Slf4jLogAdapter.addGlobalListener(interruptionWatcher);
        // No -i argument: the restarted Nucleus loads config.tlog from the same root, as a real restart would.
        kernel.parseArgs();
        // Assertion 3 below relies on the zero polling interval set in run 1; it must come back from config.tlog.
        Topic pollingFrequency =
                kernel.getContext().get(DeviceConfiguration.class).getDeploymentPollingFrequencySeconds();
        assertEquals(0L, Coerce.toLong(pollingFrequency), "deployment polling frequency after restart");
        StatusRecorder run2 = StatusRecorder.registerOn(kernel);
        List<String> previousVersionHistory = recordChangesOf(kernel, PREV_VERSION_CONFIG_KEY);
        List<String> versionHistory = recordChangesOf(kernel, VERSION_CONFIG_KEY);
        assertEquals(FAULTY_VERSION, liveValue(kernel, VERSION_CONFIG_KEY));

        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (DEPLOYMENT_SERVICE_TOPICS.equals(service.getName()) && State.RUNNING.equals(newState)) {
                deploymentServiceRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(deploymentServiceRunning.await(STEP_TIMEOUT.getSeconds(), TimeUnit.SECONDS));

        // Assertion 3: the DEFAULT launch does not re-process the interrupted cloud deployment from disk. Anything
        // offered to the queue would be picked up at once with a zero polling interval, so a quiet window and an
        // idle DeploymentService show nothing was.
        boolean requeuedLocally = waitFor(() -> !run2.statusesOf(faulty).isEmpty(), NO_REQUEUE_WINDOW);
        DeploymentService deploymentService = (DeploymentService) kernel.locate(DEPLOYMENT_SERVICE_TOPICS);
        Map<String, String> snapshotAfterRestart = recordedVersions(attemptOneDir.resolve(ROLLBACK_SNAPSHOT_FILE));
        logger.atInfo().kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY))
                .kv("livePreviousVersion", liveValue(kernel, PREV_VERSION_CONFIG_KEY))
                .kv("ongoingTarget", linkTarget(ongoingLink)).kv("snapshot", snapshotAfterRestart)
                .kv("requeuedLocally", requeuedLocally).kv("statuses", run2.statusesOf(faulty))
                .kv("pollingFrequencySeconds", Coerce.toLong(pollingFrequency))
                .log(EVIDENCE + "after restart, before re-delivery");
        assertEquals(0L, Coerce.toLong(pollingFrequency), "deployment polling frequency during the quiet window");
        assertFalse(requeuedLocally);
        assertTrue(kernel.getContext().get(DeploymentQueue.class).isEmpty());
        assertNull(deploymentService.getCurrentDeploymentTaskMetadata());
        assertEquals(FAULTY_VERSION, liveValue(kernel, VERSION_CONFIG_KEY));
        // Assertion 2: the interrupted deployment's state survived the restart, its rollback point included.
        assertTrue(Files.isSymbolicLink(ongoingLink));
        assertEquals(attemptOneDir, Files.readSymbolicLink(ongoingLink).toAbsolutePath());
        assertTrue(Files.exists(attemptOneDir.resolve(DEPLOYMENT_METADATA_FILE)));
        assertEquals(KNOWN_GOOD_VERSION, snapshotAfterRestart.get(VERSION_CONFIG_KEY));
        // No deployment has failed yet, so a previous-failure link found later belongs to the re-processed attempt.
        assertFalse(Files.isSymbolicLink(previousFailureLink));

        // Assertion 4: the cloud re-delivers the same deployment. The device last reported IN_PROGRESS, so the
        // shadow document still carries it, and the first shadow update after startup is processed again.
        Map<String, Object> reported = new HashMap<>();
        reported.put(ARN_FOR_STATUS_KEY, faulty.configurationArn);
        reported.put(STATUS_KEY, IN_PROGRESS);
        deliverThroughShadow(kernel, faulty, reported, 4);
        assertTrue(waitFor(() -> run2.statusesOf(faulty).contains(IN_PROGRESS), STEP_TIMEOUT),
                () -> run2.describe(faulty));
        assertEquals(faulty.ggDeploymentId, run2.firstStatusOf(faulty).get(GG_DEPLOYMENT_ID_KEY_NAME));
        assertTrue(waitFor(() -> run2.terminalStatusOf(faulty).isPresent(), REDELIVERY_TIMEOUT));
        // DeploymentService publishes the terminal status before it moves the deployment directory from the
        // ongoing link to previous-failure and clears its current deployment. Wait for that bookkeeping before
        // reading anything it writes.
        assertTrue(waitFor(() -> Files.isSymbolicLink(previousFailureLink) && !Files.isSymbolicLink(ongoingLink)
                        && deploymentService.getCurrentDeploymentTaskMetadata() == null, STEP_TIMEOUT),
                () -> "deployment completion bookkeeping did not finish: previous-failure="
                        + describeLink(previousFailureLink) + ", ongoing=" + describeLink(ongoingLink)
                        + ", currentDeployment="
                        + describeDeployment(deploymentService.getCurrentDeploymentTaskMetadata()));

        // Outcome of re-processing the same deployment after the restart.
        Map<String, Object> terminal = run2.terminalStatusOf(faulty).get();
        Object detailedStatus = ((Map<?, ?>) terminal.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME))
                .get(DEPLOYMENT_DETAILED_STATUS_KEY);
        Path redeliveredDir = Files.readSymbolicLink(previousFailureLink).toAbsolutePath();
        Map<String, String> redeliveredSnapshot = recordedVersions(redeliveredDir.resolve(ROLLBACK_SNAPSHOT_FILE));
        GreengrassService restartedComponent = kernel.locate(COMPONENT);
        waitFor(() -> State.RUNNING.equals(restartedComponent.getState()), Duration.ofSeconds(30));
        State finalState = restartedComponent.getState();
        // Config change subscribers run on the publish queue; drain it so the histories hold every write.
        kernel.getContext().waitForPublishQueueToClear();
        Path knownGoodArtifact = kernel.getNucleusPaths().artifactPath().resolve(COMPONENT)
                .resolve(KNOWN_GOOD_VERSION).resolve(ARTIFACT_FILE);
        Path knownGoodRecipe = kernel.getNucleusPaths().recipePath().resolve(PreloadComponentStoreHelper
                .getRecipeStorageFilenameFromTestSource(COMPONENT + "-" + KNOWN_GOOD_VERSION + ".yaml"));
        logger.atInfo().kv("status", terminal.get(DEPLOYMENT_STATUS_KEY_NAME)).kv("detailedStatus", detailedStatus)
                .kv("rollbackPointDir", redeliveredDir).kv("rollbackPoint", redeliveredSnapshot)
                .kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY))
                .kv("livePreviousVersion", liveValue(kernel, PREV_VERSION_CONFIG_KEY))
                .kv("componentState", finalState).kv("versionHistory", versionHistory)
                .kv("previousVersionHistory", previousVersionHistory)
                .kv("knownGoodArtifact", Files.exists(knownGoodArtifact))
                .kv("knownGoodRecipe", Files.exists(knownGoodRecipe))
                .kv("storedVersions", storedArtifactVersions(kernel)).kv("statuses", run2.statusesOf(faulty))
                .kv("deploymentDirFiles", fileNamesIn(redeliveredDir))
                .kv("processingState", firstLineOf(redeliveredDir.resolve("processing_state.json")))
                .kv("runTwoInterruptions", runTwoInterruptions)
                .log(EVIDENCE + "outcome");
        // Stop watching before teardown, whose own shutdown interruptions are expected; after() removes it again.
        Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        // Validity of run 2 rather than its outcome: nothing in it was interrupted, and the history recorder saw
        // the merge and rollback writes, so the history assertions below cannot pass on an empty list.
        assertThat("logged causes rooted in an interruption during run 2", runTwoInterruptions, empty());
        assertThat("previousVersion values written after the restart", previousVersionHistory, not(empty()));

        assertAll("re-processing the interrupted deployment",
                () -> assertEquals(faulty.safeDirectoryName(), String.valueOf(redeliveredDir.getFileName()),
                        "the same deployment was re-processed"),
                // Assertions 5 and 6: the rollback point must still be the known-good configuration, and the
                // rollback must land on it.
                () -> assertEquals(KNOWN_GOOD_VERSION, redeliveredSnapshot.get(VERSION_CONFIG_KEY),
                        "rollback point recorded for the re-processed deployment"),
                () -> assertEquals(OLDER_GOOD_VERSION, redeliveredSnapshot.get(PREV_VERSION_CONFIG_KEY),
                        "previousVersion in the rollback point"),
                () -> assertEquals(FAILED, terminal.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                () -> assertEquals(FAILED_ROLLBACK_COMPLETE, detailedStatus, "detailed deployment status"),
                () -> assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY),
                        "component version after rollback"),
                () -> assertEquals(State.RUNNING, finalState, "component state after rollback"),
                // Assertion 7: previousVersion is never rotated onto the faulty version, the pre-deployment value
                // is back after rollback, and the known-good version is still in the component store.
                () -> assertThat("previousVersion values written after the restart", previousVersionHistory,
                        not(hasItem(FAULTY_VERSION))),
                () -> assertThat("previousVersion values written after the restart", previousVersionHistory,
                        hasItem(OLDER_GOOD_VERSION)),
                () -> assertEquals(OLDER_GOOD_VERSION, liveValue(kernel, PREV_VERSION_CONFIG_KEY),
                        "previousVersion after rollback"),
                () -> assertTrue(Files.exists(knownGoodArtifact), "known-good artifact still stored"),
                () -> assertTrue(Files.exists(knownGoodRecipe), "known-good recipe still stored"),
                () -> assertNotEquals(FAULTY_VERSION, liveValue(kernel, VERSION_CONFIG_KEY),
                        "the faulty version must not survive the rollback"));
    }

    private Kernel launchFirstNucleus() throws Exception {
        Kernel firstKernel = new Kernel();
        NoOpPathOwnershipHandler.register(firstKernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(firstKernel,
                InterruptedDeploymentRedeliveryRollbackTest.class.getResource("onlyMain.yaml"));
        // A zero polling interval makes DeploymentService pick up queued deployments immediately.
        setDeviceConfig(firstKernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 0L);

        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        firstKernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (DEPLOYMENT_SERVICE_TOPICS.equals(service.getName()) && State.RUNNING.equals(newState)) {
                deploymentServiceRunning.countDown();
            }
        });
        firstKernel.launch();
        assertTrue(deploymentServiceRunning.await(STEP_TIMEOUT.getSeconds(), TimeUnit.SECONDS));
        ((FleetStatusService) firstKernel.locate(FLEET_STATUS_SERVICE_TOPICS)).getIsConnected().set(false);
        return firstKernel;
    }

    /**
     * Put one version's recipe and artifact in the component store. Staging only the version a deployment targets
     * mirrors a cloud download, and keeps the store's stale-version cleanup meaningful.
     */
    private static void stageInComponentStore(Kernel kernel, String version) throws Exception {
        Path storeContent = Paths.get(
                InterruptedDeploymentRedeliveryRollbackTest.class.getResource("interrupted_redelivery").toURI());
        String recipeFile = COMPONENT + "-" + version + ".yaml";
        Files.copy(storeContent.resolve("recipes").resolve(recipeFile), kernel.getNucleusPaths().recipePath()
                .resolve(PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource(recipeFile)),
                REPLACE_EXISTING);
        Path artifactDir = kernel.getNucleusPaths().artifactPath().resolve(COMPONENT).resolve(version);
        Files.createDirectories(artifactDir);
        copyFolderRecursively(storeContent.resolve("artifacts").resolve(COMPONENT).resolve(version), artifactDir,
                REPLACE_EXISTING);
    }

    /**
     * Hand a deployment shadow document to the shadow listener, the way the listener receives one from the
     * shadow update/get subscriptions.
     */
    private static void deliverThroughShadow(Kernel kernel, DeploymentStep step, Map<String, Object> reported,
                                             int shadowVersion) {
        Map<String, Object> desired = new HashMap<>();
        desired.put(ShadowDeploymentListener.FLEET_CONFIG_KEY, step.fleetConfiguration);
        desired.put(ShadowDeploymentListener.DESIRED_STATUS_KEY, SUCCEEDED);
        kernel.getContext().get(ShadowDeploymentListener.class)
                .triggerShadowUpdatedForTest(desired, reported, shadowVersion);
    }

    private static String linkTarget(Path link) throws IOException {
        return Files.isSymbolicLink(link) ? String.valueOf(Files.readSymbolicLink(link)) : "<no link>";
    }

    // For failure messages, which cannot throw.
    private static String describeLink(Path link) {
        try {
            return linkTarget(link);
        } catch (IOException e) {
            return "<unreadable: " + e + ">";
        }
    }

    // For failure messages: DeploymentTaskMetadata has no toString().
    private static String describeDeployment(DeploymentTaskMetadata metadata) {
        return metadata == null ? "<none>" : metadata.getDeploymentId();
    }

    private static String liveValue(Kernel kernel, String key) {
        Topics serviceTopics = kernel.findServiceTopic(COMPONENT);
        return serviceTopics == null ? null : Coerce.toString(serviceTopics.find(key));
    }

    /**
     * Record every value a component config key is set to from now on, including values written by merges and
     * rollback that a single read at the end would miss.
     */
    private static List<String> recordChangesOf(Kernel kernel, String key) {
        List<String> history = new CopyOnWriteArrayList<>();
        kernel.findServiceTopic(COMPONENT).subscribe((WhatHappened what, Node child) -> {
            if (child instanceof Topic && key.equals(child.getName())) {
                history.add(Coerce.toString(((Topic) child).getOnce()));
            }
        });
        return history;
    }

    private static Map<String, String> recordedVersions(Path transactionLog) throws IOException {
        Map<String, String> versions = new HashMap<>();
        try (Context readContext = new Context()) {
            com.aws.greengrass.config.Configuration recorded =
                    ConfigurationReader.createFromTLog(readContext, transactionLog);
            Topics componentTopics = recorded.findTopics(SERVICES_NAMESPACE_TOPIC, COMPONENT);
            if (componentTopics != null) {
                versions.put(VERSION_CONFIG_KEY, Coerce.toString(componentTopics.find(VERSION_CONFIG_KEY)));
                versions.put(PREV_VERSION_CONFIG_KEY, Coerce.toString(componentTopics.find(PREV_VERSION_CONFIG_KEY)));
            }
        }
        return versions;
    }

    private static List<String> storedArtifactVersions(Kernel kernel) throws IOException {
        Path componentArtifacts = kernel.getNucleusPaths().artifactPath().resolve(COMPONENT);
        if (!Files.isDirectory(componentArtifacts)) {
            return Collections.emptyList();
        }
        try (Stream<Path> versions = Files.list(componentArtifacts)) {
            return versions.map(p -> String.valueOf(p.getFileName())).sorted().collect(Collectors.toList());
        }
    }

    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> String.valueOf(p.getFileName())).sorted().collect(Collectors.toList());
        }
    }

    // Evidence only: the attempt record the fix keeps in the deployment directory. Absent on code that predates it.
    private static String firstLineOf(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllLines(file).stream().findFirst().orElse("") : "<absent>";
    }

    private static boolean waitFor(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return Boolean.TRUE.equals(condition.get());
    }

    /**
     * One revision of the thing deployment, carrying the fleet configuration the cloud puts in the shadow.
     */
    private static final class DeploymentStep {
        private final String configurationArn;
        private final String ggDeploymentId;
        private final String fleetConfiguration;

        private DeploymentStep(String configurationArn, String ggDeploymentId, String fleetConfiguration) {
            this.configurationArn = configurationArn;
            this.ggDeploymentId = ggDeploymentId;
            this.fleetConfiguration = fleetConfiguration;
        }

        static DeploymentStep of(int revision, String componentVersion, long creationTimestamp) throws IOException {
            String configurationArn = THING_CONFIGURATION_ARN_PREFIX + revision;
            String ggDeploymentId = String.format("00000000-0000-4000-8000-%012d", revision);
            Map<String, Object> componentUpdatePolicy = new HashMap<>();
            componentUpdatePolicy.put("timeout", 60);
            componentUpdatePolicy.put("action", "NOTIFY_COMPONENTS");
            Map<String, Object> document = new HashMap<>();
            document.put("deploymentId", ggDeploymentId);
            document.put("configurationArn", configurationArn);
            document.put("components", Collections.singletonMap(COMPONENT,
                    Collections.singletonMap("version", componentVersion)));
            document.put("creationTimestamp", creationTimestamp);
            document.put("failureHandlingPolicy", "ROLLBACK");
            document.put("componentUpdatePolicy", componentUpdatePolicy);
            document.put("configurationValidationPolicy", Collections.singletonMap("timeout", 60));
            String fleetConfiguration = OBJECT_MAPPER.writeValueAsString(document);
            // Fail fast on a malformed document rather than on a rejected deployment later.
            OBJECT_MAPPER.readValue(fleetConfiguration, Configuration.class);
            return new DeploymentStep(configurationArn, ggDeploymentId, fleetConfiguration);
        }

        String safeDirectoryName() {
            return ggDeploymentId.replace(':', '.').replace('/', '+');
        }
    }

    /**
     * Deployment statuses as DeploymentStatusKeeper publishes them to cloud reporters.
     */
    private static final class StatusRecorder {
        private final List<Map<String, Object>> statuses = new CopyOnWriteArrayList<>();

        static StatusRecorder registerOn(Kernel kernel) {
            StatusRecorder recorder = new StatusRecorder();
            kernel.getContext().get(DeploymentStatusKeeper.class).registerDeploymentStatusConsumer(
                    DeploymentType.SHADOW, recorder::record, InterruptedDeploymentRedeliveryRollbackTest.class
                            .getSimpleName());
            return recorder;
        }

        Boolean record(Map<String, Object> status) {
            statuses.add(status);
            return Boolean.TRUE;
        }

        List<String> statusesOf(DeploymentStep step) {
            List<String> result = new ArrayList<>();
            for (Map<String, Object> status : statuses) {
                if (step.configurationArn.equals(status.get(CONFIGURATION_ARN_KEY_NAME))) {
                    result.add(Coerce.toString(status.get(DEPLOYMENT_STATUS_KEY_NAME)));
                }
            }
            return result;
        }

        String describe(DeploymentStep step) {
            return "statuses of " + step.configurationArn + ": " + statuses.stream()
                    .filter(s -> step.configurationArn.equals(s.get(CONFIGURATION_ARN_KEY_NAME)))
                    .map(s -> s.get(DEPLOYMENT_STATUS_KEY_NAME) + " " + s.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME))
                    .collect(Collectors.toList());
        }

        Map<String, Object> firstStatusOf(DeploymentStep step) {
            return statuses.stream().filter(s -> step.configurationArn.equals(s.get(CONFIGURATION_ARN_KEY_NAME)))
                    .findFirst().orElse(Collections.emptyMap());
        }

        Optional<Map<String, Object>> terminalStatusOf(DeploymentStep step) {
            return statuses.stream().filter(s -> step.configurationArn.equals(s.get(CONFIGURATION_ARN_KEY_NAME)))
                    .filter(s -> TERMINAL_STATUSES.contains(Coerce.toString(s.get(DEPLOYMENT_STATUS_KEY_NAME))))
                    .findFirst();
        }
    }
}
