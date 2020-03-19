/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.deployment.DeploymentTask;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
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
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(PerformanceReporting.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeploymentServiceIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";

    //Based on the recipe files of the packages in sample job document
    private static final String TEST_CUSTOMER_APP_STRING_UPDATED = "Hello evergreen. This is a new value";
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "Go ahead with 2 approvals";
    private static final Path LOCAL_CACHE_PATH = Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    private static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE,
            false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Logger logger;

    private static DependencyResolver dependencyResolver;
    private static PackageCache packageCache;
    private static KernelConfigResolver kernelConfigResolver;

    private DeploymentDocument sampleDeploymentDocument;
    private static Kernel kernel;

    private static Map<String, Long> outputMessagesToTimestamp;
    private CountDownLatch countDownLatch;

    @TempDir
    static Path sharedDir;

    @BeforeAll
    static void setupLogger() {
        System.setProperty("log.level", "INFO");
        System.setProperty("log.fmt", "JSON");
        System.setProperty("log.store", "CONSOLE");
        outputMessagesToTimestamp = new HashMap<>();
        logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
    }

    @BeforeAll
    public static void setupKernel() {
        kernel = new Kernel();
        kernel.parseArgs("-r", sharedDir.toString(), "-i",
                DeploymentServiceIntegrationTest.class.getResource("onlyMain.yaml").toString());
        kernel.launch();
        dependencyResolver = new DependencyResolver(new LocalPackageStore(LOCAL_CACHE_PATH), kernel);
        packageCache = new PackageCache();
        kernelConfigResolver = new KernelConfigResolver(packageCache, kernel);
    }

    @AfterAll
    public static void tearDown() {
        kernel.shutdown();
    }

    @Test
    @Order(1)
    public void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel()
            throws Exception {
        outputMessagesToTimestamp.clear();
        final List<String> listOfExpectedMessages = Arrays.asList(TEST_TICK_TOCK_STRING, TEST_MOSQUITTO_STRING,
                TEST_CUSTOMER_APP_STRING);
        countDownLatch = new CountDownLatch(3);
        Consumer<EvergreenStructuredLogMessage> listener = m->{
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if(messageOnStdout != null && listOfExpectedMessages.contains(messageOnStdout)) {
                //TODO: Deduping is needed, as currently kernel is running the GreenSignal and Mosquitto dependencies
                // multiple times before the CustomerApp runs. This should not be the expected behavior. Sim to
                // capture this https://sim.amazon.com/issues/P34042537
                if(!outputMessagesToTimestamp.containsKey(messageOnStdout)) {
                    outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                    countDownLatch.countDown();
                }
            }
        };
        Log4jLogEventBuilder.addGlobalListener(listener);
        Future<?> result = submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument.json").toURI(), System.currentTimeMillis());

        result.get();

        countDownLatch.await(60, TimeUnit.SECONDS);
        Set<String> listOfStdoutMessagesTapped = outputMessagesToTimestamp.keySet();
        assertThat(listOfStdoutMessagesTapped, Matchers.containsInAnyOrder(Matchers.equalTo(TEST_CUSTOMER_APP_STRING)
                , Matchers.equalTo(TEST_MOSQUITTO_STRING), Matchers.equalTo(TEST_TICK_TOCK_STRING)));
        Log4jLogEventBuilder.removeGlobalListener(listener);
    }

    @Test
    @Order(2)
    public void GIVEN_services_running_WHEN_updated_params_THEN_services_start_with_updated_params_in_kernel()
            throws Exception {
        outputMessagesToTimestamp.clear();
        countDownLatch = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> listener = m->{
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if(messageOnStdout != null && messageOnStdout.equals(TEST_CUSTOMER_APP_STRING_UPDATED)) {
                outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                countDownLatch.countDown();
            }
        };
        Log4jLogEventBuilder.addGlobalListener(listener);

        Future<?> result = submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument_updated.json").toURI(), System.currentTimeMillis());
        result.get();
        countDownLatch.await(60, TimeUnit.SECONDS);
        assertTrue(outputMessagesToTimestamp.containsKey(TEST_CUSTOMER_APP_STRING_UPDATED));
        Log4jLogEventBuilder.removeGlobalListener(listener);
    }

    private Future<?> submitSampleJobDocument(URI uri, Long timestamp) {

        try {
            sampleDeploymentDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        } catch (Exception e) {
            fail("Failed to create Deployment document object from sample job document", e.getCause());
        }
        sampleDeploymentDocument.setTimestamp(timestamp);
        DeploymentTask deploymentTask = new DeploymentTask(dependencyResolver, packageCache, kernelConfigResolver,
                kernel, logger, sampleDeploymentDocument);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> resultOfSubmission = executorService.submit(deploymentTask);
        return resultOfSubmission;
    }
}