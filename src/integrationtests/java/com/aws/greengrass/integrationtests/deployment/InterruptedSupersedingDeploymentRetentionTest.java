/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.ShadowDeploymentListener;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
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
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.status.model.DeploymentInformation.ARN_FOR_STATUS_KEY;
import static com.aws.greengrass.status.model.DeploymentInformation.STATUS_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessageSubstring;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interrupted deployment superseded by a second deployment that is itself interrupted. The second deployment
 * carries the first one's rollback snapshot forward as its rollback baseline, so every component version that
 * snapshot names has to stay in the component store until a deployment succeeds: the stale-version cleanup otherwise
 * judges staleness by live config, which by then holds the unverified versions of both deployments rather than the
 * baseline. An interrupted attempt skips that cleanup, and one that reaches a failed result keeps what the snapshot
 * names.
 *
 * <p>com.example.RollbackRepro runs the known-good 01.05.00. Thing deployment :565 moves it to the faulty
 * 01.05.22.84 and is interrupted mid-activation; after a restart, thing deployment :566 supersedes it with the
 * equally faulty 01.06.00 and is interrupted mid-activation too. After a second restart the cloud re-delivers :566.
 * In one test it fails and rolls back to the carried baseline, 01.05.00. In the other it fails before it is activated,
 * which keeps the baseline as the next deployment's, and the next deployment, :567, rolls back to it.
 *
 * <p>Boundaries not crossed are those of {@link InterruptedDeploymentRedeliveryRollbackTest}: graceful in-JVM
 * shutdowns instead of power loss, and shadow documents handed to the shadow listener directly.
 */
class InterruptedSupersedingDeploymentRetentionTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(InterruptedSupersedingDeploymentRetentionTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String EVIDENCE = "[SUPERSEDE-RETENTION] ";

    private static final String COMPONENT = "com.example.RollbackRepro";
    private static final String OLDER_GOOD_VERSION = "01.04.01";
    private static final String KNOWN_GOOD_VERSION = "01.05.00";
    // Built rather than written out because PMD's AvoidUsingHardCodedIP reads a four-part dotted literal as an
    // IPv4 address.
    private static final String FAULTY_VERSION = String.format("%02d.%02d.%02d.%02d", 1, 5, 22, 84);
    private static final String SUPERSEDING_VERSION = "01.06.00";
    private static final String ARTIFACT_FILE = "rollbackRepro.txt";
    // Synthetic thing deployment ARN: example account, no customer identifiers.
    private static final String THING_CONFIGURATION_ARN_PREFIX =
            "arn:aws:greengrass:us-east-1:123456789012:configuration:thing/RollbackReproThing:";

    private static final String ONGOING_LINK = "ongoing";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String FAILED_ROLLBACK_COMPLETE = "FAILED_ROLLBACK_COMPLETE";
    private static final String FAILED_UNABLE_TO_ROLLBACK = "FAILED_UNABLE_TO_ROLLBACK";
    private static final List<String> TERMINAL_STATUSES = Collections.unmodifiableList(
            Arrays.asList(SUCCEEDED, FAILED, "REJECTED", "CANCELED"));

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REDELIVERY_TIMEOUT = Duration.ofSeconds(150);

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
    void GIVEN_interrupted_deployment_superseded_by_one_also_interrupted_WHEN_it_rolls_back_THEN_baseline_versions_kept(
            ExtensionContext context) throws Exception {
        // The faulty versions breaking, and the interruptions landing inside activation, are the scenario itself.
        // ExceptionLogProtector cannot withdraw an allowance, so the interruption allowance is test-wide; run 3, which
        // nothing interrupts, is watched for one separately.
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);
        TwoInterruptedDeployments interrupted = interruptTwoDeployments();

        // ---------------- Nucleus run 3: the cloud re-delivers :566, which fails and rolls back ----------------
        List<String> runThreeInterruptions = watchInterruptions();
        kernel = launchNucleus(false);
        StatusRecorder run3 = StatusRecorder.registerOn(kernel);
        deliverThroughShadow(kernel, interrupted.superseding, reportedInProgress(interrupted.superseding), 5);
        assertTrue(waitFor(() -> run3.terminalStatusOf(interrupted.superseding).isPresent(), REDELIVERY_TIMEOUT),
                () -> run3.describe(interrupted.superseding));
        DeploymentService deploymentService = (DeploymentService) kernel.locate(DEPLOYMENT_SERVICE_TOPICS);
        // The terminal status is published before the deployment directory moves to previous-failure.
        assertTrue(waitFor(() -> Files.isSymbolicLink(interrupted.previousFailureLink)
                && !Files.isSymbolicLink(interrupted.ongoingLink)
                && deploymentService.getCurrentDeploymentTaskMetadata() == null, STEP_TIMEOUT));
        GreengrassService component = kernel.locate(COMPONENT);
        waitFor(() -> State.RUNNING.equals(component.getState()), Duration.ofSeconds(30));
        kernel.getContext().waitForPublishQueueToClear();
        Map<String, Object> terminal = run3.terminalStatusOf(interrupted.superseding).get();
        Object detailedStatus = ((Map<?, ?>) terminal.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME))
                .get(DEPLOYMENT_DETAILED_STATUS_KEY);
        State finalState = component.getState();
        Path previousFailureTarget = Files.readSymbolicLink(interrupted.previousFailureLink).toAbsolutePath();
        logger.atInfo().kv("status", terminal.get(DEPLOYMENT_STATUS_KEY_NAME)).kv("detailedStatus", detailedStatus)
                .kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY)).kv("componentState", finalState)
                .kv("storedVersions", storedVersions(kernel)).kv("previousFailure", previousFailureTarget)
                .kv("supersededStatuses", run3.statusesOf(interrupted.faulty))
                .kv("runThreeInterruptions", runThreeInterruptions).log(EVIDENCE + "outcome");
        // Stop watching before teardown, whose own shutdown interruptions are expected; after() removes it again.
        Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        assertThat("logged causes rooted in an interruption during run 3", runThreeInterruptions, empty());
        assertAll("re-processing the superseding deployment",
                () -> assertEquals(FAILED, terminal.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                () -> assertEquals(FAILED_ROLLBACK_COMPLETE, detailedStatus, "detailed deployment status"),
                () -> assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY),
                        "component version after rollback"),
                () -> assertEquals(State.RUNNING, finalState, "component state after rollback"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionArtifact),
                        "known-good artifact stored after rollback"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionRecipe),
                        "known-good recipe stored after rollback"),
                () -> assertTrue(Files.exists(interrupted.baseline.previousVersionArtifact),
                        "older known-good artifact stored after rollback"),
                () -> assertTrue(Files.exists(interrupted.baseline.previousVersionRecipe),
                        "older known-good recipe stored after rollback"));
        // The superseded deployment was never reported: superseding it set it aside without a status, and the
        // directory kept as the last failed deployment's is the superseding one's.
        assertAll("the superseded deployment",
                () -> assertThat("statuses of the superseded deployment in run 3",
                        run3.statusesOf(interrupted.faulty), empty()),
                () -> assertEquals(interrupted.supersedingDir, previousFailureTarget, "previous-failure link"));
    }

    @Test
    void GIVEN_superseding_deployment_redelivered_WHEN_it_fails_before_activation_THEN_baseline_versions_kept_for_rollback(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);
        // How the re-delivery is made to fail before activation: see run 3.
        ignoreExceptionUltimateCauseWithMessageSubstring(context, "no local component version satisfies");
        TwoInterruptedDeployments interrupted = interruptTwoDeployments();
        DeploymentStep later = DeploymentStep.of(567, FAULTY_VERSION, interrupted.creationTimestamp + 4);

        // ------- Nucleus run 3: the re-delivered :566 fails before it is activated, then :567 rolls back -------
        List<String> runThreeInterruptions = watchInterruptions();
        kernel = launchNucleus(false);
        StatusRecorder run3 = StatusRecorder.registerOn(kernel);
        DeploymentService deploymentService = (DeploymentService) kernel.locate(DEPLOYMENT_SERVICE_TOPICS);
        // A local deployment pinned the component meanwhile, so the re-delivery cannot be resolved: a failure that
        // reaches a result without anything being activated, as a download or resolution error does.
        Topic localPin = deploymentService.getConfig().lookup(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS,
                LOCAL_DEPLOYMENT_GROUP_NAME, COMPONENT, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY);
        localPin.withValue(KNOWN_GOOD_VERSION);
        kernel.getContext().waitForPublishQueueToClear();
        deliverThroughShadow(kernel, interrupted.superseding, reportedInProgress(interrupted.superseding), 5);
        assertTrue(waitFor(() -> run3.terminalStatusOf(interrupted.superseding).isPresent(), REDELIVERY_TIMEOUT),
                () -> run3.describe(interrupted.superseding));
        assertTrue(waitFor(() -> deploymentService.getCurrentDeploymentTaskMetadata() == null, STEP_TIMEOUT));
        Map<String, Object> failed = run3.terminalStatusOf(interrupted.superseding).get();
        Object failedDetailedStatus = ((Map<?, ?>) failed.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME))
                .get(DEPLOYMENT_DETAILED_STATUS_KEY);
        List<String> storedAfterFailure = storedVersions(kernel);
        logger.atInfo().kv("status", failed.get(DEPLOYMENT_STATUS_KEY_NAME))
                .kv("detailedStatus", failedDetailedStatus).kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY))
                .kv("ongoingTarget", linkTarget(interrupted.ongoingLink)).kv("storedVersions", storedAfterFailure)
                .log(EVIDENCE + "re-delivery failed before activation");
        assertAll("the re-delivery failed before activation and kept the carried baseline",
                () -> assertEquals(FAILED, failed.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                // Its first attempt applied 01.06.00, which nothing undid.
                () -> assertEquals(FAILED_UNABLE_TO_ROLLBACK, failedDetailedStatus, "detailed deployment status"),
                () -> assertEquals(interrupted.supersedingDir,
                        Files.readSymbolicLink(interrupted.ongoingLink).toAbsolutePath(), "ongoing link"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionArtifact), "known-good artifact"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionRecipe), "known-good recipe"),
                () -> assertTrue(Files.exists(interrupted.baseline.previousVersionArtifact),
                        "older known-good artifact"),
                () -> assertTrue(Files.exists(interrupted.baseline.previousVersionRecipe), "older known-good recipe"));

        // The pin is lifted, and the next deployment carries the baseline forward and rolls back to it.
        deploymentService.getConfig().findTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS,
                LOCAL_DEPLOYMENT_GROUP_NAME).remove();
        kernel.getContext().waitForPublishQueueToClear();
        deliverThroughShadow(kernel, later, reportedInProgress(interrupted.superseding), 6);
        assertTrue(waitFor(() -> run3.terminalStatusOf(later).isPresent(), REDELIVERY_TIMEOUT),
                () -> run3.describe(later));
        assertTrue(waitFor(() -> deploymentService.getCurrentDeploymentTaskMetadata() == null, STEP_TIMEOUT));
        GreengrassService component = kernel.locate(COMPONENT);
        waitFor(() -> State.RUNNING.equals(component.getState()), Duration.ofSeconds(30));
        kernel.getContext().waitForPublishQueueToClear();
        Map<String, Object> rolledBack = run3.terminalStatusOf(later).get();
        Object detailedStatus = ((Map<?, ?>) rolledBack.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME))
                .get(DEPLOYMENT_DETAILED_STATUS_KEY);
        State finalState = component.getState();
        logger.atInfo().kv("status", rolledBack.get(DEPLOYMENT_STATUS_KEY_NAME)).kv("detailedStatus", detailedStatus)
                .kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY)).kv("componentState", finalState)
                .kv("storedVersions", storedVersions(kernel)).kv("runThreeInterruptions", runThreeInterruptions)
                .log(EVIDENCE + "outcome after the next deployment");
        Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        assertThat("logged causes rooted in an interruption during run 3", runThreeInterruptions, empty());
        assertAll("the next deployment rolls back to the carried baseline",
                () -> assertEquals(FAILED, rolledBack.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                () -> assertEquals(FAILED_ROLLBACK_COMPLETE, detailedStatus, "detailed deployment status"),
                () -> assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY),
                        "component version after rollback"),
                () -> assertEquals(OLDER_GOOD_VERSION, liveValue(kernel, PREV_VERSION_CONFIG_KEY),
                        "previous version after rollback"),
                () -> assertEquals(State.RUNNING, finalState, "component state after rollback"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionArtifact), "known-good artifact"),
                () -> assertTrue(Files.exists(interrupted.baseline.versionRecipe), "known-good recipe"));
    }

    /**
     * Nucleus runs 1 and 2, shared by both tests. Run 1 reaches 01.05.00, then interrupts :565 mid-activation; run 2
     * delivers :566, which supersedes :565, carries its rollback snapshot forward and is interrupted mid-activation too.
     */
    private TwoInterruptedDeployments interruptTwoDeployments() throws Exception {
        long creationTimestamp = System.currentTimeMillis();
        DeploymentStep olderGood = DeploymentStep.of(563, OLDER_GOOD_VERSION, creationTimestamp);
        DeploymentStep knownGood = DeploymentStep.of(564, KNOWN_GOOD_VERSION, creationTimestamp + 1);
        DeploymentStep faulty = DeploymentStep.of(565, FAULTY_VERSION, creationTimestamp + 2);
        DeploymentStep superseding = DeploymentStep.of(566, SUPERSEDING_VERSION, creationTimestamp + 3);

        // ---------------- Nucleus run 1: reach 01.05.00, then interrupt :565 mid-activation ----------------
        kernel = launchNucleus(true);
        StatusRecorder run1 = StatusRecorder.registerOn(kernel);
        stageInComponentStore(kernel, OLDER_GOOD_VERSION);
        deliverThroughShadow(kernel, olderGood, null, 1);
        assertTrue(waitFor(() -> run1.statusesOf(olderGood).contains(SUCCEEDED), STEP_TIMEOUT),
                () -> run1.describe(olderGood));
        stageInComponentStore(kernel, KNOWN_GOOD_VERSION);
        deliverThroughShadow(kernel, knownGood, null, 2);
        assertTrue(waitFor(() -> run1.statusesOf(knownGood).contains(SUCCEEDED), STEP_TIMEOUT),
                () -> run1.describe(knownGood));
        assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY));
        stageInComponentStore(kernel, FAULTY_VERSION);
        deliverThroughShadow(kernel, faulty, null, 3);
        interruptOnceApplied(kernel, FAULTY_VERSION);
        assertThat(run1.statusesOf(faulty), contains(IN_PROGRESS));

        Path deploymentsDir = kernel.getNucleusPaths().deploymentPath();
        Path ongoingLink = deploymentsDir.resolve(ONGOING_LINK);
        Path previousFailureLink = deploymentsDir.resolve(PREVIOUS_FAILURE_LINK);
        Path faultyDir = Files.readSymbolicLink(ongoingLink).toAbsolutePath();
        StoredBaseline baseline = new StoredBaseline(kernel);
        kernel.shutdown();
        logger.atInfo().kv("ongoingTarget", linkTarget(ongoingLink)).kv("storedVersions", storedVersions(kernel))
                .log(EVIDENCE + "after interrupting :565");

        // ---------------- Nucleus run 2: :566 supersedes :565 and is interrupted mid-activation too ----------------
        kernel = launchNucleus(false);
        StatusRecorder run2 = StatusRecorder.registerOn(kernel);
        stageInComponentStore(kernel, SUPERSEDING_VERSION);
        deliverThroughShadow(kernel, superseding, reportedInProgress(faulty), 4);
        interruptOnceApplied(kernel, SUPERSEDING_VERSION);
        assertThat(run2.statusesOf(superseding), contains(IN_PROGRESS));
        Path supersedingDir = Files.readSymbolicLink(ongoingLink).toAbsolutePath();
        List<String> supersededStatusesInRun2 = run2.statusesOf(faulty);
        kernel.shutdown();

        // The superseding deployment's rollback baseline is the snapshot carried from :565, which names 01.05.00, so
        // 01.05.00 must still be stored now that both deployments are interrupted.
        Map<String, String> carriedBaseline = recordedVersions(supersedingDir.resolve(ROLLBACK_SNAPSHOT_FILE));
        List<String> storedAfterRun2 = storedVersions(kernel);
        logger.atInfo().kv("supersedingDir", supersedingDir).kv("carriedBaseline", carriedBaseline)
                .kv("storedVersions", storedAfterRun2).kv("knownGoodArtifact", Files.exists(baseline.versionArtifact))
                .kv("knownGoodRecipe", Files.exists(baseline.versionRecipe))
                .kv("supersededDirectoryExists", Files.exists(faultyDir))
                .kv("supersededStatuses", supersededStatusesInRun2).log(EVIDENCE + "after interrupting :566");
        assertEquals(superseding.safeDirectoryName(), String.valueOf(supersedingDir.getFileName()));
        assertEquals(KNOWN_GOOD_VERSION, carriedBaseline.get(VERSION_CONFIG_KEY), "carried rollback baseline");
        assertEquals(OLDER_GOOD_VERSION, carriedBaseline.get(PREV_VERSION_CONFIG_KEY), "carried baseline previous");
        assertAll("versions the carried baseline names survive the interrupted attempts",
                () -> assertTrue(Files.exists(baseline.versionArtifact), "known-good artifact still stored"),
                () -> assertTrue(Files.exists(baseline.versionRecipe), "known-good recipe still stored"),
                () -> assertTrue(Files.exists(baseline.previousVersionArtifact),
                        "older known-good artifact still stored"),
                () -> assertTrue(Files.exists(baseline.previousVersionRecipe),
                        "older known-good recipe still stored"));
        // Superseding :565 carried its snapshot and removed its directory, without reporting it: it is left for the
        // cloud, which superseded it.
        assertAll("the superseded deployment",
                () -> assertThat("statuses of the superseded deployment in run 2", supersededStatusesInRun2,
                        empty()),
                () -> assertFalse(Files.exists(faultyDir), "superseded deployment directory removed"),
                () -> assertFalse(Files.isSymbolicLink(previousFailureLink), "no previous-failure link yet"));
        return new TwoInterruptedDeployments(creationTimestamp, faulty, superseding, supersedingDir, ongoingLink,
                previousFailureLink, baseline);
    }

    private List<String> watchInterruptions() {
        List<String> interruptions = new CopyOnWriteArrayList<>();
        interruptionWatcher = message -> {
            if (message.getCause() != null
                    && Utils.getUltimateCause(message.getCause()) instanceof InterruptedException) {
                interruptions.add(message.getThread() + " " + message.getLoggerName() + ": "
                        + message.getMessage());
            }
        };
        Slf4jLogAdapter.addGlobalListener(interruptionWatcher);
        return interruptions;
    }

    private static Map<String, Object> reportedInProgress(DeploymentStep step) {
        Map<String, Object> reported = new HashMap<>();
        reported.put(ARN_FOR_STATUS_KEY, step.configurationArn);
        reported.put(STATUS_KEY, IN_PROGRESS);
        return reported;
    }

    private Kernel launchNucleus(boolean firstRun) throws Exception {
        Kernel newKernel = new Kernel();
        NoOpPathOwnershipHandler.register(newKernel);
        if (firstRun) {
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(newKernel,
                    InterruptedSupersedingDeploymentRetentionTest.class.getResource("onlyMain.yaml"));
            // A zero polling interval makes DeploymentService pick up queued deployments immediately.
            setDeviceConfig(newKernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 0L);
        } else {
            // No -i argument: a restarted Nucleus loads config.tlog from the same root.
            newKernel.parseArgs();
        }
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        newKernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (DEPLOYMENT_SERVICE_TOPICS.equals(service.getName()) && State.RUNNING.equals(newState)) {
                deploymentServiceRunning.countDown();
            }
        });
        newKernel.launch();
        assertTrue(deploymentServiceRunning.await(STEP_TIMEOUT.getSeconds(), TimeUnit.SECONDS));
        ((FleetStatusService) newKernel.locate(FLEET_STATUS_SERVICE_TOPICS)).getIsConnected().set(false);
        return newKernel;
    }

    /**
     * Wait until DefaultActivator has merged the given version, so the deployment's change is durable but has no
     * result yet, then interrupt the Nucleus. The faulty startup steps take 5 seconds to fail.
     */
    private static void interruptOnceApplied(Kernel kernel, String version) throws Exception {
        assertTrue(waitFor(() -> version.equals(liveValue(kernel, VERSION_CONFIG_KEY)), STEP_TIMEOUT));
        GreengrassService component = kernel.locate(COMPONENT);
        assertTrue(waitFor(() -> State.STARTING.equals(component.getState()), STEP_TIMEOUT));
        kernel.getContext().waitForPublishQueueToClear();
    }

    private static void stageInComponentStore(Kernel kernel, String version) throws Exception {
        Path storeContent = Paths.get(
                InterruptedSupersedingDeploymentRetentionTest.class.getResource("interrupted_redelivery").toURI());
        String recipeFile = COMPONENT + "-" + version + ".yaml";
        Files.copy(storeContent.resolve("recipes").resolve(recipeFile), kernel.getNucleusPaths().recipePath()
                .resolve(PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource(recipeFile)),
                REPLACE_EXISTING);
        Path artifactDir = kernel.getNucleusPaths().artifactPath().resolve(COMPONENT).resolve(version);
        Files.createDirectories(artifactDir);
        copyFolderRecursively(storeContent.resolve("artifacts").resolve(COMPONENT).resolve(version), artifactDir,
                REPLACE_EXISTING);
    }

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

    private static String liveValue(Kernel kernel, String key) {
        Topics serviceTopics = kernel.findServiceTopic(COMPONENT);
        return serviceTopics == null ? null : Coerce.toString(serviceTopics.find(key));
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

    private static List<String> storedVersions(Kernel kernel) throws IOException {
        Path componentArtifacts = kernel.getNucleusPaths().artifactPath().resolve(COMPONENT);
        if (!Files.isDirectory(componentArtifacts)) {
            return Collections.emptyList();
        }
        try (Stream<Path> versions = Files.list(componentArtifacts)) {
            return versions.map(p -> String.valueOf(p.getFileName())).sorted().collect(Collectors.toList());
        }
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
     * Where the component store keeps the recipes and artifacts of the versions the carried baseline names: its
     * version, 01.05.00, and its previous version, 01.04.01.
     */
    private static final class StoredBaseline {
        private final Path versionArtifact;
        private final Path versionRecipe;
        private final Path previousVersionArtifact;
        private final Path previousVersionRecipe;

        StoredBaseline(Kernel kernel) {
            versionArtifact = artifactOf(kernel, KNOWN_GOOD_VERSION);
            versionRecipe = recipeOf(kernel, KNOWN_GOOD_VERSION);
            previousVersionArtifact = artifactOf(kernel, OLDER_GOOD_VERSION);
            previousVersionRecipe = recipeOf(kernel, OLDER_GOOD_VERSION);
        }

        private static Path artifactOf(Kernel kernel, String version) {
            return kernel.getNucleusPaths().artifactPath().resolve(COMPONENT).resolve(version).resolve(ARTIFACT_FILE);
        }

        private static Path recipeOf(Kernel kernel, String version) {
            return kernel.getNucleusPaths().recipePath().resolve(PreloadComponentStoreHelper
                    .getRecipeStorageFilenameFromTestSource(COMPONENT + "-" + version + ".yaml"));
        }
    }

    /**
     * What runs 1 and 2 leave behind: :565 superseded by :566, both interrupted mid-activation.
     */
    private static final class TwoInterruptedDeployments {
        private final long creationTimestamp;
        private final DeploymentStep faulty;
        private final DeploymentStep superseding;
        private final Path supersedingDir;
        private final Path ongoingLink;
        private final Path previousFailureLink;
        private final StoredBaseline baseline;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        TwoInterruptedDeployments(long creationTimestamp, DeploymentStep faulty, DeploymentStep superseding,
                                  Path supersedingDir, Path ongoingLink, Path previousFailureLink,
                                  StoredBaseline baseline) {
            this.creationTimestamp = creationTimestamp;
            this.faulty = faulty;
            this.superseding = superseding;
            this.supersedingDir = supersedingDir;
            this.ongoingLink = ongoingLink;
            this.previousFailureLink = previousFailureLink;
            this.baseline = baseline;
        }
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
                    DeploymentType.SHADOW, recorder::record, InterruptedSupersedingDeploymentRetentionTest.class
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

        Optional<Map<String, Object>> terminalStatusOf(DeploymentStep step) {
            return statuses.stream().filter(s -> step.configurationArn.equals(s.get(CONFIGURATION_ARN_KEY_NAME)))
                    .filter(s -> TERMINAL_STATUSES.contains(Coerce.toString(s.get(DEPLOYMENT_STATUS_KEY_NAME))))
                    .findFirst();
        }
    }
}
