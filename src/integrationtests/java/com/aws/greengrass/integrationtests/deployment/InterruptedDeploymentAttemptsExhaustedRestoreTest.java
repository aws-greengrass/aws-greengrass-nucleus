/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.ConfigurationReader;
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

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_STACK_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.CONFIGURATION_ARN_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment interrupted on every allowed attempt is restored at the next launch, before any service starts, and
 * its re-deliveries are then failed and refused. This is the path the attempt cap exists for; it runs the real
 * KernelLifecycle, so the configuration the restored launch boots is checked rather than mocked.
 *
 * <p>com.example.RollbackRepro runs the known-good 01.05.00 with maxInterruptedDeploymentAttempts at 1. Thing
 * deployment :565 moves it to the faulty 01.05.22.84 and is interrupted mid-activation, which uses its only attempt.
 * The restarted Nucleus boots the rollback snapshot, so 01.05.00 runs again without the faulty version starting.
 * The cloud then re-delivers :565, which is failed as restored, and a re-delivery that was in flight meanwhile is
 * rejected after a further restart.
 *
 * <p>Boundaries not crossed are those of {@link InterruptedDeploymentRedeliveryRollbackTest}: graceful in-JVM
 * shutdowns instead of power loss, and shadow documents handed to the shadow listener directly.
 */
class InterruptedDeploymentAttemptsExhaustedRestoreTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(InterruptedDeploymentAttemptsExhaustedRestoreTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String EVIDENCE = "[EXHAUSTED-RESTORE] ";

    private static final String COMPONENT = "com.example.RollbackRepro";
    private static final String OLDER_GOOD_VERSION = "01.04.01";
    private static final String KNOWN_GOOD_VERSION = "01.05.00";
    // Built rather than written out because PMD's AvoidUsingHardCodedIP reads a four-part dotted literal as an
    // IPv4 address.
    private static final String FAULTY_VERSION = String.format("%02d.%02d.%02d.%02d", 1, 5, 22, 84);
    // Synthetic thing deployment ARN: example account, no customer identifiers.
    private static final String THING_CONFIGURATION_ARN_PREFIX =
            "arn:aws:greengrass:us-east-1:123456789012:configuration:thing/RollbackReproThing:";

    private static final String ONGOING_LINK = "ongoing";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String CONFIG_TLOG_FILE = "config.tlog";
    private static final String PROCESSING_STATE_FILE = "processing_state.json";

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String REJECTED = "REJECTED";
    private static final String FAILED_ROLLBACK_COMPLETE = "FAILED_ROLLBACK_COMPLETE";
    private static final List<String> TERMINAL_STATUSES = Collections.unmodifiableList(
            Arrays.asList(SUCCEEDED, FAILED, REJECTED, "CANCELED"));

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(60);

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
    void GIVEN_deployment_interrupted_on_its_only_allowed_attempt_WHEN_restarted_THEN_restored_before_boot_and_failed_then_rejected(
            ExtensionContext context) throws Exception {
        // The faulty version breaking, and the interruption landing inside activation, are the scenario itself; so
        // are the failure and the rejection that report the used-up attempts. The interruption allowance cannot be
        // withdrawn, so it is test-wide; runs 2 and 3, which nothing interrupts, are watched for one separately.
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);
        ignoreExceptionUltimateCauseWithMessageSubstring(context, "on every allowed attempt");

        long creationTimestamp = System.currentTimeMillis();
        DeploymentStep olderGood = DeploymentStep.of(563, OLDER_GOOD_VERSION, creationTimestamp);
        DeploymentStep knownGood = DeploymentStep.of(564, KNOWN_GOOD_VERSION, creationTimestamp + 1);
        DeploymentStep faulty = DeploymentStep.of(565, FAULTY_VERSION, creationTimestamp + 2);

        // ---------------- Nucleus run 1: reach 01.05.00, then interrupt :565 on its only attempt ----------------
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                InterruptedDeploymentAttemptsExhaustedRestoreTest.class.getResource("onlyMain.yaml"));
        // A zero polling interval makes DeploymentService pick up queued deployments immediately.
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 0L);
        setDeviceConfig(kernel, DeviceConfiguration.MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, 1);
        launch(kernel);
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
        // Wait until DefaultActivator has merged the faulty version, so its change is durable with no result yet.
        // The faulty startup step takes 5 seconds to fail.
        assertTrue(waitFor(() -> FAULTY_VERSION.equals(liveValue(kernel, VERSION_CONFIG_KEY)), STEP_TIMEOUT));
        GreengrassService component = kernel.locate(COMPONENT);
        assertTrue(waitFor(() -> State.STARTING.equals(component.getState()), STEP_TIMEOUT));
        kernel.getContext().waitForPublishQueueToClear();
        assertThat(run1.statusesOf(faulty), contains(IN_PROGRESS));

        Path deploymentsDir = kernel.getNucleusPaths().deploymentPath();
        Path ongoingLink = deploymentsDir.resolve(ONGOING_LINK);
        Path previousFailureLink = deploymentsDir.resolve(PREVIOUS_FAILURE_LINK);
        Path faultyDir = Files.readSymbolicLink(ongoingLink).toAbsolutePath();
        Path configTlog = kernel.getNucleusPaths().configPath().resolve(CONFIG_TLOG_FILE);
        kernel.shutdown();
        assertEquals(FAULTY_VERSION, recordedVersions(configTlog).get(VERSION_CONFIG_KEY));
        logger.atInfo().kv("record", firstLineOf(faultyDir.resolve(PROCESSING_STATE_FILE)))
                .kv("configTlog", recordedVersions(configTlog)).log(EVIDENCE + "after interrupting :565");
        // The attempt counter is what tells whether the deployment is ever applied again: the one application so
        // far was counted.
        assertEquals(1, processingAttemptsOf(faultyDir), "attempts counted in run 1");

        // ---------------- Nucleus run 2: the launch restores, then the cloud re-delivers :565 ----------------
        List<String> laterInterruptions = new CopyOnWriteArrayList<>();
        interruptionWatcher = message -> {
            if (message.getCause() != null
                    && Utils.getUltimateCause(message.getCause()) instanceof InterruptedException) {
                laterInterruptions.add(message.getThread() + " " + message.getLoggerName() + ": "
                        + message.getMessage());
            }
        };
        Slf4jLogAdapter.addGlobalListener(interruptionWatcher);
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        // No -i argument: the restarted Nucleus loads config.tlog from the same root, as a real restart would.
        kernel.parseArgs();
        // Restored before anything started: what the launch loaded, and what config.tlog now holds, is the
        // configuration from before the deployment.
        Map<String, String> restoredTlog = recordedVersions(configTlog);
        String restoredLiveVersion = liveValue(kernel, VERSION_CONFIG_KEY);
        String restoredLivePrevious = liveValue(kernel, PREV_VERSION_CONFIG_KEY);
        String recordAfterRestore = firstLineOf(faultyDir.resolve(PROCESSING_STATE_FILE));
        logger.atInfo().kv("liveVersion", restoredLiveVersion).kv("livePreviousVersion", restoredLivePrevious)
                .kv("configTlog", restoredTlog).kv("record", recordAfterRestore)
                .log(EVIDENCE + "after the restoring launch parsed its arguments");
        assertAll("the launch restored the configuration from before the deployment",
                () -> assertEquals(KNOWN_GOOD_VERSION, restoredLiveVersion, "live version"),
                () -> assertEquals(OLDER_GOOD_VERSION, restoredLivePrevious, "live previous version"),
                () -> assertEquals(KNOWN_GOOD_VERSION, restoredTlog.get(VERSION_CONFIG_KEY), "config.tlog version"),
                () -> assertThat("record", recordAfterRestore, containsString("\"launchRecovery\":\"RESTORED\"")));
        List<String> versionHistory = recordChangesOf(kernel, VERSION_CONFIG_KEY);
        launch(kernel);
        StatusRecorder run2 = StatusRecorder.registerOn(kernel);
        GreengrassService restoredComponent = kernel.locate(COMPONENT);
        assertTrue(waitFor(() -> State.RUNNING.equals(restoredComponent.getState()), STEP_TIMEOUT));

        Map<String, Object> reportedInProgress = new HashMap<>();
        reportedInProgress.put(ARN_FOR_STATUS_KEY, faulty.configurationArn);
        reportedInProgress.put(STATUS_KEY, IN_PROGRESS);
        deliverThroughShadow(kernel, faulty, reportedInProgress, 4);
        assertTrue(waitFor(() -> run2.terminalStatusOf(faulty).isPresent(), STEP_TIMEOUT),
                () -> run2.describe(faulty));
        DeploymentService deploymentService = (DeploymentService) kernel.locate(DEPLOYMENT_SERVICE_TOPICS);
        // The terminal status is published before the deployment directory moves to previous-failure.
        assertTrue(waitFor(() -> Files.isSymbolicLink(previousFailureLink) && !Files.isSymbolicLink(ongoingLink)
                && deploymentService.getCurrentDeploymentTaskMetadata() == null, STEP_TIMEOUT));
        kernel.getContext().waitForPublishQueueToClear();
        Map<String, Object> failed = run2.terminalStatusOf(faulty).get();
        Map<?, ?> failedDetails = (Map<?, ?>) failed.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
        State stateAfterFailure = restoredComponent.getState();
        int attemptsAfterRedelivery = processingAttemptsOf(faultyDir);
        logger.atInfo().kv("status", failed.get(DEPLOYMENT_STATUS_KEY_NAME)).kv("details", failedDetails)
                .kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY)).kv("componentState", stateAfterFailure)
                .kv("versionHistory", versionHistory).kv("attempts", attemptsAfterRedelivery)
                .log(EVIDENCE + "re-delivery after the restore");
        assertAll("the re-delivered deployment is failed as restored, without being applied again",
                () -> assertEquals(FAILED, failed.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                () -> assertEquals(FAILED_ROLLBACK_COMPLETE, failedDetails.get(DEPLOYMENT_DETAILED_STATUS_KEY),
                        "detailed deployment status"),
                () -> assertThat("failure cause", String.valueOf(failedDetails.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                        containsString("The device has been restored to the configuration from before the "
                                + "deployment")),
                // Failed by the used-up attempts, not by an activation.
                () -> assertThat("error stack", String.valueOf(failedDetails.get(DEPLOYMENT_ERROR_STACK_KEY)),
                        containsString("DEPLOYMENT_INTERRUPTED")),
                () -> assertEquals(1, attemptsAfterRedelivery, "attempts counted after the re-delivery"),
                () -> assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY), "live version"),
                () -> assertEquals(State.RUNNING, stateAfterFailure, "component state"),
                // The recorder saw the version it subscribed to, so an empty remainder means nothing was written.
                () -> assertThat("version when the recorder subscribed",
                        versionHistory.stream().findFirst().orElse("<nothing recorded>"), is(KNOWN_GOOD_VERSION)),
                () -> assertThat("versions written after the restore", versionHistory, not(hasItem(FAULTY_VERSION))));
        // A graceful shutdown between runs logs interruptions of its own.
        Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        kernel.shutdown();
        Slf4jLogAdapter.addGlobalListener(interruptionWatcher);

        // ---------------- Nucleus run 3: a re-delivery that was in flight when the failure was reported ----------
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        kernel.parseArgs();
        launch(kernel);
        StatusRecorder run3 = StatusRecorder.registerOn(kernel);
        deliverThroughShadow(kernel, faulty, reportedInProgress, 5);
        assertTrue(waitFor(() -> run3.terminalStatusOf(faulty).isPresent(), STEP_TIMEOUT),
                () -> run3.describe(faulty));
        Map<String, Object> rejected = run3.terminalStatusOf(faulty).get();
        Map<?, ?> rejectedDetails = (Map<?, ?>) rejected.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
        logger.atInfo().kv("status", rejected.get(DEPLOYMENT_STATUS_KEY_NAME)).kv("details", rejectedDetails)
                .kv("liveVersion", liveValue(kernel, VERSION_CONFIG_KEY)).kv("laterInterruptions", laterInterruptions)
                .log(EVIDENCE + "outcome");
        // Stop watching before teardown, whose own shutdown interruptions are expected; after() removes it again.
        Slf4jLogAdapter.removeGlobalListener(interruptionWatcher);
        assertThat("logged causes rooted in an interruption during runs 2 and 3", laterInterruptions, empty());
        int attemptsAfterRefusal = processingAttemptsOf(faultyDir);
        assertAll("a later re-delivery is refused rather than given a fresh allowance",
                () -> assertEquals(REJECTED, rejected.get(DEPLOYMENT_STATUS_KEY_NAME), "deployment status"),
                () -> assertEquals(1, attemptsAfterRefusal, "attempts counted after the refusal"),
                () -> assertThat("failure cause", String.valueOf(rejectedDetails.get(DEPLOYMENT_FAILURE_CAUSE_KEY)),
                        containsString("the device has been restored to the configuration from before it")),
                () -> assertEquals(KNOWN_GOOD_VERSION, liveValue(kernel, VERSION_CONFIG_KEY), "live version"));
    }

    private static void launch(Kernel kernel) throws Exception {
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (DEPLOYMENT_SERVICE_TOPICS.equals(service.getName()) && State.RUNNING.equals(newState)) {
                deploymentServiceRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(deploymentServiceRunning.await(STEP_TIMEOUT.getSeconds(), TimeUnit.SECONDS));
        ((FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS)).getIsConnected().set(false);
    }

    private static void stageInComponentStore(Kernel kernel, String version) throws Exception {
        Path storeContent = Paths.get(InterruptedDeploymentAttemptsExhaustedRestoreTest.class
                .getResource("interrupted_redelivery").toURI());
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

    private static String liveValue(Kernel kernel, String key) {
        Topics serviceTopics = kernel.findServiceTopic(COMPONENT);
        return serviceTopics == null ? null : Coerce.toString(serviceTopics.find(key));
    }

    /**
     * Record every value a component config key is set to from now on, including values written by a merge that a
     * single read at the end would miss. The first entry is the value when the recording starts, which subscribing
     * delivers right away: it shows the recorder is live, so a history with nothing after it means nothing was
     * written.
     */
    private static List<String> recordChangesOf(Kernel kernel, String key) {
        List<String> history = new CopyOnWriteArrayList<>();
        kernel.findServiceTopic(COMPONENT).find(key).subscribe((what, topic) ->
                history.add(Coerce.toString(topic.getOnce())));
        return history;
    }

    private static int processingAttemptsOf(Path deploymentDir) throws IOException {
        Map<?, ?> record = OBJECT_MAPPER.readValue(deploymentDir.resolve(PROCESSING_STATE_FILE).toFile(), Map.class);
        return Coerce.toInt(record.get("processingAttempts"));
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
        private final String fleetConfiguration;

        private DeploymentStep(String configurationArn, String fleetConfiguration) {
            this.configurationArn = configurationArn;
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
            return new DeploymentStep(configurationArn, fleetConfiguration);
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
                    DeploymentType.SHADOW, recorder::record, InterruptedDeploymentAttemptsExhaustedRestoreTest.class
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
