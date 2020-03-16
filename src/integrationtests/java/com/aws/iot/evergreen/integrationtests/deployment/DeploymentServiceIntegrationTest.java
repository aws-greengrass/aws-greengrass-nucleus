/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.deployment.DeploymentTask;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(PerformanceReporting.class)
public class DeploymentServiceIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";

    //Based on the recipe files of the packages in sample job document
    private static final String TEST_CUSTOMER_APP_STRING_UPDATED = "Hello evergreen. This is a new value";
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "No tick-tocking with period: 2";
    private static final Path LOCAL_CACHE_PATH = Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    static ObjectMapper OBJECT_MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE,
            false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static Logger logger;
    static final String LogFileName = "deploymentIntegTest.log";

    private static DependencyResolver dependencyResolver;
    private static PackageCache packageCache;
    private static KernelConfigResolver kernelConfigResolver;

    DeploymentDocument sampleDeploymentDocument;
    Kernel kernel;

    @BeforeAll
    static void setupLogFile() {
        System.setProperty("log.fmt", "JSON");
        System.setProperty("log.storeName", LogFileName);
        System.setProperty("log.store", "FILE");
        System.setProperty("log.level", "INFO");
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
            Files.createFile(Paths.get(LogFileName));
            logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
        } catch (IOException e) {
            fail("Failed to create log file", e);
        }
    }

    @AfterAll
    static void cleanup() {
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
        } catch (IOException e) {
            fail("Failed to delete log file", e);
        }
    }

    @BeforeEach
    public void setupKernelAndLogFile() {

        try {
            String tdir = System.getProperty("user.home");
            kernel = new Kernel();
            kernel.parseArgs("-r", tdir, "-log", "stdout", "-i",
                    DeploymentServiceIntegrationTest.class.getResource("deploymentDemo.yaml").toString());
            kernel.launch();
            dependencyResolver = new DependencyResolver(new LocalPackageStore(LOCAL_CACHE_PATH), kernel.context);
            packageCache = new PackageCache();
            kernelConfigResolver = new KernelConfigResolver(packageCache, kernel);

            File fileToWatch = new File(LogFileName);
            while (!fileToWatch.exists()) {
                Thread.sleep(1000);
            }
        } catch(Exception e) {
            fail("Caught exception while setting up test");
        }
    }

    @Test
    public void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel()
            throws Exception {
        Future<?> result = submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument.json").toURI());

        try {
            result.get();
        } catch (ExecutionException e) {
            fail("Failed executing the deployment task", e.getCause());
        }
        String logLines = new String(Files.readAllBytes(Paths.get(LogFileName)));
        int tickTokLogStringIndex = logLines.indexOf(TEST_TICK_TOCK_STRING);
        int mosquittoLogStringIndex = logLines.indexOf(TEST_MOSQUITTO_STRING);
        int customerAppLogStringIndex = logLines.indexOf(TEST_CUSTOMER_APP_STRING);
        assertTrue(tickTokLogStringIndex != -1);
        assertTrue(mosquittoLogStringIndex != -1);
        assertTrue(customerAppLogStringIndex != -1);
        //TODO: Check the order of indexes as per dependency
        kernel.shutdown();
    }

    @Test
    public void GIVEN_services_running_WHEN_updated_params_THEN_services_start_with_updated_params_in_kernel()
            throws Exception {

        Future<?> result = submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument.json").toURI());
        try {
            result.get();
        } catch (ExecutionException e) {
            fail("Failed executing the deployment task", e.getCause());
        }

        result = submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument_updated.json").toURI());
        try {
            result.get();
        } catch (ExecutionException e) {
            fail("Failed executing the updated deployment task", e.getCause());
        }
        String logLines = new String(Files.readAllBytes(Paths.get(LogFileName)));
        int customerAppLogStringIndex = logLines.indexOf(TEST_CUSTOMER_APP_STRING_UPDATED);
        assertTrue(customerAppLogStringIndex != -1);
        kernel.shutdown();
    }

    private Future<?> submitSampleJobDocument(URI uri) {
        File fileToWatch = new File(LogFileName);
        while (!fileToWatch.exists()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        try {
            sampleDeploymentDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        } catch (Exception e) {
            fail("Failed to create Deployment document object from sample job document", e.getCause());
        }
        sampleDeploymentDocument.setTimestamp(System.currentTimeMillis());
        DeploymentTask deploymentTask = new DeploymentTask(dependencyResolver, packageCache, kernelConfigResolver,
                kernel, logger, sampleDeploymentDocument);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> resultOfSubmission = executorService.submit(deploymentTask);
        return resultOfSubmission;
    }
}
