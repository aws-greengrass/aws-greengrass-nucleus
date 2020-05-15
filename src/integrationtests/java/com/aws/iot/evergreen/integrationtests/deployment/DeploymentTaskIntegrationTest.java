/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.DeploymentTask;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
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
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeploymentTaskIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";

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

    @TempDir
    static Path rootDir;

    @BeforeAll
    static void setupLogger() {
        outputMessagesToTimestamp = new HashMap<>();
        logger = LogManager.getLogger(DeploymentTaskIntegrationTest.class);
    }

    @BeforeAll
    static void setupKernel() throws IOException {
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
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").getPath());
        copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());
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
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel()
            throws Exception {
        outputMessagesToTimestamp.clear();
        final List<String> listOfExpectedMessages =
                Arrays.asList(TEST_TICK_TOCK_STRING, TEST_MOSQUITTO_STRING, TEST_CUSTOMER_APP_STRING);
        countDownLatch = new CountDownLatch(3);
        Consumer<EvergreenStructuredLogMessage> listener = m -> {
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if (messageOnStdout != null && listOfExpectedMessages.contains(messageOnStdout)) {
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
            if (messageOnStdout != null && messageOnStdout.equals(TEST_CUSTOMER_APP_STRING_UPDATED)) {
                outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                countDownLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

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
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        //should contain main, YellowSignal, CustomerApp, Mosquitto and GreenSignal
        assertEquals(5, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "CustomerApp", "Mosquitto", "GreenSignal"));

        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

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
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, YellowSignal and RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));

        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureDoNothingDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

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
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, YellowSignal and RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));

        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureRollbackDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        // should contain main, YellowSignal, RedSignal
        assertEquals(3, services.size());
        assertThat(services, containsInAnyOrder("main", "YellowSignal", "RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("BreakingService"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Future<DeploymentResult> submitSampleJobDocument(URI uri, Long timestamp) throws Exception {
        sampleJobDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        sampleJobDocument.setTimestamp(timestamp);
        DeploymentTask deploymentTask =
                new DeploymentTask(dependencyResolver, packageManager, kernelConfigResolver, deploymentConfigMerger,
                        logger, sampleJobDocument);
        return executorService.submit(deploymentTask);
    }

    private static void copyFolderRecursively(Path src, Path des) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(des.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, des.resolve(src.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
