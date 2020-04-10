/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.deployment.DeploymentTask;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceHelper;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeploymentServiceIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";

    // Based on the recipe files of the packages in sample job document
    private static final String TEST_CUSTOMER_APP_STRING_UPDATED = "Hello evergreen. This is a new value";
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "Go ahead with 2 approvals";

    private static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Logger logger;

    private static DependencyResolver dependencyResolver;
    private static PackageStore packageStore;
    private static KernelConfigResolver kernelConfigResolver;

    private DeploymentDocument sampleDeploymentDocument;
    private static Kernel kernel;

    private static Map<String, Long> outputMessagesToTimestamp;
    private CountDownLatch countDownLatch;

    @TempDir
    static Path rootDir;

    @BeforeAll
    static void setupLogger() {
        outputMessagesToTimestamp = new HashMap<>();
        logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
    }

    @BeforeAll
    static void setupKernel() throws IOException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
        kernel = new Kernel();
        kernel.parseArgs("-i", DeploymentServiceIntegrationTest.class.getResource("onlyMain.yaml").toString());
        kernel.launch();

        // initialize packageStore and dependencyResolver
        packageStore = new PackageStore(kernel.packageStorePath, new GreengrassPackageServiceHelper(),
                new GreengrassRepositoryDownloader(), Executors.newSingleThreadExecutor(), kernel);

        Path localStoreContentPath = Paths.get(DeploymentServiceIntegrationTest.class.getResource(
                "local_store_content").getPath());

        // pre-load contents to package store
        copyFolderRecursively(localStoreContentPath, kernel.packageStorePath);

        dependencyResolver = new DependencyResolver(packageStore, kernel);
        kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
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
        Log4jLogEventBuilder.addGlobalListener(listener);
        Future<?> result = submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("SampleJobDocument.json").toURI(),
                System.currentTimeMillis());

        result.get(60, TimeUnit.SECONDS);

        countDownLatch.await(60, TimeUnit.SECONDS);
        Set<String> listOfStdoutMessagesTapped = outputMessagesToTimestamp.keySet();
        assertThat(listOfStdoutMessagesTapped, Matchers.containsInAnyOrder(Matchers.equalTo(TEST_CUSTOMER_APP_STRING),
                Matchers.equalTo(TEST_MOSQUITTO_STRING), Matchers.equalTo(TEST_TICK_TOCK_STRING)));
        Log4jLogEventBuilder.removeGlobalListener(listener);
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
        Log4jLogEventBuilder.addGlobalListener(listener);

        Future<?> result = submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("SampleJobDocument_updated.json").toURI(),
                System.currentTimeMillis());
        result.get(30, TimeUnit.SECONDS);
        countDownLatch.await(60, TimeUnit.SECONDS);
        assertTrue(outputMessagesToTimestamp.containsKey(TEST_CUSTOMER_APP_STRING_UPDATED));
        Log4jLogEventBuilder.removeGlobalListener(listener);
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

        Future<?> result = submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("CustomerAppAndYellowSignal.json").toURI(),
                System.currentTimeMillis());
        result.get(30, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        //should contain main, YellowSignal, CustomerApp, Mosquitto and GreenSignal
        assertEquals(5, services.size());
        assertTrue(services.contains("main"));
        assertTrue(services.contains("YellowSignal"));
        assertTrue(services.contains("CustomerApp"));
        assertTrue(services.contains("Mosquitto"));
        assertTrue(services.contains("GreenSignal"));

        result = submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        result.get(30, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(evergreenService -> evergreenService.getName()).collect(Collectors.toList());

        //"should contain main, YellowSignal, RedSignal"
        assertEquals(3, services.size());
        assertTrue(services.contains("main"));
        assertTrue(services.contains("YellowSignal"));
        assertTrue(services.contains("RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("CustomerApp"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Future<?> submitSampleJobDocument(URI uri, Long timestamp) {
        try {
            sampleDeploymentDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        } catch (Exception e) {
            fail("Failed to create Deployment document object from sample job document", e.getCause());
        }
        sampleDeploymentDocument.setTimestamp(timestamp);
        DeploymentTask deploymentTask =
                new DeploymentTask(dependencyResolver, packageStore, kernelConfigResolver, kernel, logger,
                        sampleDeploymentDocument);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        return executorService.submit(deploymentTask);
    }

    private static void copyFolderRecursively(Path src, Path des)
            throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(des.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, des.resolve(src.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
