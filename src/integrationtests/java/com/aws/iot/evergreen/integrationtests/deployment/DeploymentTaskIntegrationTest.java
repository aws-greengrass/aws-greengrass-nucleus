/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DefaultDeploymentTask;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.DeploymentDirectoryManager;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.lifecycle.Lifecycle;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;
import com.aws.iot.evergreen.ipc.services.lifecycle.PreComponentUpdateEvent;
import com.aws.iot.evergreen.ipc.services.lifecycle.exceptions.LifecycleIPCException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.iot.evergreen.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeploymentTaskIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";
    private static final String MOCK_GROUP_NAME = "thinggroup/group1";

    // Based on the recipe files of the packages in sample job document
    private static final String TEST_CUSTOMER_APP_STRING_UPDATED = "Hello evergreen. This is a new value";
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "Go ahead with 2 approvals";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Logger logger;

    private static DependencyResolver dependencyResolver;
    private static PackageManager packageManager;
    private static KernelConfigResolver kernelConfigResolver;
    private static DeploymentConfigMerger deploymentConfigMerger;

    private DeploymentDocument sampleJobDocument;
    private static Kernel kernel;

    private static Map<String, Long> outputMessagesToTimestamp;
    private CountDownLatch countDownLatch;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Topics groupToRootComponentsTopics;
    private Topics deploymentServiceTopics;

    private final AtomicInteger deploymentCount = new AtomicInteger();

    @TempDir
    static Path rootDir;

    @BeforeAll
    static void setupLogger() {
        outputMessagesToTimestamp = new HashMap<>();
        logger = LogManager.getLogger(DeploymentTaskIntegrationTest.class);
    }

    @BeforeAll
    static void setupKernel() throws IOException, URISyntaxException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
        kernel = new Kernel();
        kernel.parseArgs("-i", DeploymentTaskIntegrationTest.class.getResource("onlyMain.yaml").toString());

        kernel.launch();

        // get required instances from context
        packageManager = kernel.getContext().get(PackageManager.class);
        dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        kernelConfigResolver = kernel.getContext().get(KernelConfigResolver.class);
        deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        // pre-load contents to package store
        Path localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
        copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath(), REPLACE_EXISTING);
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        deploymentServiceTopics = Topics.of(kernel.getContext(), DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                null);
        groupToRootComponentsTopics = deploymentServiceTopics.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, MOCK_GROUP_NAME);
    }

    @AfterEach
    void afterEach() {
        executorService.shutdownNow();
    }

    @AfterAll
    static void tearDown() {
        kernel.shutdown();
    }

    @Test
    @Order(1)
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel(ExtensionContext context)
            throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("raw",
                EvergreenService.class.getName());
        outputMessagesToTimestamp.clear();
        final List<String> listOfExpectedMessages =
                Arrays.asList(TEST_TICK_TOCK_STRING, TEST_MOSQUITTO_STRING, TEST_CUSTOMER_APP_STRING);
        countDownLatch = new CountDownLatch(3);
        Consumer<EvergreenStructuredLogMessage> listener = m -> {
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if (messageOnStdout == null) {
                return;
            }
            // Windows has quotes in the echo, so strip them
            messageOnStdout = messageOnStdout.replaceAll("\"", "");
            if (listOfExpectedMessages.contains(messageOnStdout)) {
                //TODO: Deduping is needed, as currently kernel is running the GreenSignal and Mosquitto dependencies
                // multiple times before the CustomerApp runs. This should not be the expected behavior. Sim to
                // capture this https://sim.amazon.com/issues/P34042537
                if (!outputMessagesToTimestamp.containsKey(messageOnStdout)) {
                    outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                    countDownLatch.countDown();
                }
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SampleJobDocument.json").toURI(),
                System.currentTimeMillis());

        resultFuture.get(60, TimeUnit.SECONDS);

        countDownLatch.await(60, TimeUnit.SECONDS);
        Set<String> listOfStdoutMessagesTapped = outputMessagesToTimestamp.keySet();
        assertThat(listOfStdoutMessagesTapped, containsInAnyOrder(Matchers.equalTo(TEST_CUSTOMER_APP_STRING),
                Matchers.equalTo(TEST_MOSQUITTO_STRING), Matchers.equalTo(TEST_TICK_TOCK_STRING)));
        Slf4jLogAdapter.removeGlobalListener(listener);

        // Check that ClassService is a raw EvergreenService and not a GenericExternalService
        assertEquals(EvergreenService.class, kernel.locate("ClassService").getClass());
    }

    @Test
    @Order(2)
    void GIVEN_services_running_WHEN_updated_params_THEN_services_start_with_updated_params_in_kernel()
            throws Exception {
        outputMessagesToTimestamp.clear();
        countDownLatch = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> listener = m -> {
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if (messageOnStdout == null) {
                return;
            }
            // Windows has quotes in the echo, so strip them
            messageOnStdout = messageOnStdout.replaceAll("\"", "");
            if (messageOnStdout.equals(TEST_CUSTOMER_APP_STRING_UPDATED)) {
                outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                countDownLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        groupToRootComponentsTopics.lookup("CustomerApp").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SampleJobDocument_updated.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        countDownLatch.await(60, TimeUnit.SECONDS);
        assertTrue(outputMessagesToTimestamp.containsKey(TEST_CUSTOMER_APP_STRING_UPDATED));
        Slf4jLogAdapter.removeGlobalListener(listener);
    }

    /**
     * First deployment contains packages yellow and customerApp
     * Second deployment updates the root packages to yellow and red. Red is added, customerApp is removed
     * and no update for yellow
     *
     * @throws Exception
     */
    @Test
    @Order(3)
    void GIVEN_services_running_WHEN_service_added_and_deleted_THEN_add_remove_service_accordingly() throws Exception {

        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("CustomerAppAndYellowSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        //should contain main, YellowSignal, CustomerApp, Mosquitto and GreenSignal
        assertEquals(5, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "CustomerApp", "Mosquitto", "GreenSignal"));
        groupToRootComponentsTopics.lookup("CustomerApp").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToRootComponentsTopics.lookup("YellowSignal").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        //"should contain main, YellowSignal, RedSignal"
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("CustomerApp"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
    }

    /**
     * First deployment starts some services. Second deployment tries to add a service that breaks
     * and removes an existing service but the failure handling policy is to do nothing
     * As a result, no corrective action will be taken on failure
     *
     * @throws Exception
     */
    @Test
    @Order(4)
    void GIVEN_services_running_WHEN_new_service_breaks_failure_handling_policy_do_nothing_THEN_service_stays_broken(
            ExtensionContext context) throws Exception {
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        // should contain main, YellowSignal and RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));
        groupToRootComponentsTopics.lookup("RedSignal").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToRootComponentsTopics.lookup("YellowSignal").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureDoNothingDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        // should contain main, RedSignal, BreakingService, Mosquitto and GreenSignal
        assertEquals(5, services.size());
        assertThat(services, containsInAnyOrder("main", "RedSignal", "BreakingService", "Mosquitto", "GreenSignal"));
        assertEquals(State.BROKEN, kernel.locate("BreakingService").getState());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, result.getDeploymentStatus());
    }

    /**
     * First deployment starts some services. Second deployment tries to add a service that breaks
     * and removes an existing service and the failure handling policy is to rollback
     * As a result, kernel should be reverted to the state before deployment
     *
     * @throws Exception
     */
    @Test
    @Order(5)
    void GIVEN_services_running_WHEN_new_service_breaks_failure_handling_policy_rollback_THEN_services_are_rolled_back(
            ExtensionContext context) throws Exception {
        Map<String, Object> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        groupToRootComponentsTopics.lookup("RedSignal").withValue(
                pkgDetails);
        groupToRootComponentsTopics.lookup("YellowSignal").withValue(
                pkgDetails);
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        // should contain main, YellowSignal and RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));

        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);
        groupToRootComponentsTopics.lookup("YellowSignal").remove();
        groupToRootComponentsTopics.lookup("BreakingService").withValue(
                ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureRollbackDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(60, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        // should contain main, YellowSignal, RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("BreakingService"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
    }

    @Test
    @Order(6)
    void GIVEN_deployment_in_progress_WHEN_deployment_task_is_cancelled_THEN_stop_processing() throws Exception {
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("AddNewServiceWithSafetyCheck.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);

        KernelIPCClientConfig nonDisruptable = getIPCConfigForService("NonDisruptableService", kernel);
        IPCClientImpl ipcClient = new IPCClientImpl(nonDisruptable);
        Lifecycle lifecycle = new LifecycleImpl(ipcClient);

        lifecycle.subscribeToComponentUpdate((event) -> {
            if (event instanceof PreComponentUpdateEvent) {
                try {
                    lifecycle.deferComponentUpdate("NonDisruptableService", TimeUnit.SECONDS.toMillis(60));
                    ipcClient.disconnect();
                } catch (LifecycleIPCException e) { }
            }
        });

        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, NonDisruptableService 1.0.0
        assertEquals(2, services.size(), "Actual services: " + services);
        assertThat(services, containsInAnyOrder("main", "NonDisruptableService"));

        CountDownLatch cdlUpdateStarted = new CountDownLatch(1);
        CountDownLatch cdlMergeCancelled = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().contains("deferred by NonDisruptableService")) {
                cdlUpdateStarted.countDown();
            }
            if (m.getMessage() != null && m.getMessage().contains("Cancelled deployment merge future due to interrupt")) {
                cdlMergeCancelled.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("UpdateServiceWithSafetyCheck.json").toURI(),
                System.currentTimeMillis());

        assertTrue(cdlUpdateStarted.await(40, TimeUnit.SECONDS));
        resultFuture.cancel(true);

        assertTrue(cdlMergeCancelled.await(30, TimeUnit.SECONDS));

        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, NonDisruptableService 1.0.0
        assertEquals(2, services.size());
        assertThat(services, containsInAnyOrder("main", "NonDisruptableService"));
        assertThat(services, containsInAnyOrder("main", "NonDisruptableService"));
        assertEquals("1.0.0", kernel.findServiceTopic("NonDisruptableService")
                .find("version").getOnce());

        Slf4jLogAdapter.removeGlobalListener(listener);
    }

    @Test
    @Order(7)
    void GIVEN_services_running_WHEN_new_deployment_asks_to_skip_safety_check_THEN_deployment_is_successful() throws Exception {
        // The previous test has NonDisruptableService 1.0.0 running in kernel that always returns false when its
        // safety check script is run, this test demonstrates that when a next deployment configured to skip safety
        // check is processed, it can still update the NonDisruptableService service to version 1.0.1 bypassing the
        // safety check
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SkipSafetyCheck.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, NonDisruptableService 1.0.1
        assertEquals(2, services.size(), "Existing services: " + services);
        assertThat(services, containsInAnyOrder("main", "NonDisruptableService"));
        assertEquals("1.0.1", kernel.findServiceTopic("NonDisruptableService")
                .find("version").getOnce());
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Future<DeploymentResult> submitSampleJobDocument(URI uri, Long timestamp) throws Exception {
        kernel.getContext().get(DeploymentDirectoryManager.class).createNewDeploymentDirectoryIfNotExists(
                "testFleetConfigArn" + deploymentCount.getAndIncrement());

        sampleJobDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        sampleJobDocument.setTimestamp(timestamp);
        sampleJobDocument.setGroupName(MOCK_GROUP_NAME);
        DefaultDeploymentTask deploymentTask = new DefaultDeploymentTask(dependencyResolver, packageManager,
                kernelConfigResolver, deploymentConfigMerger, logger, new Deployment(sampleJobDocument,
                Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT),
                deploymentServiceTopics);
        return executorService.submit(deploymentTask);
    }
}
